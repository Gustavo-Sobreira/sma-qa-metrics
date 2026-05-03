package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

import org.bson.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class CodeAnalyzerAgent extends Agent {

    private static final Gson gson = GsonProvider.get();
    private static final Set<String> REQUIRED_AGENTS = Set.of("sonar");
    private static final String SONAR_METRICS = String.join(",",
            "bugs",
            "reliability_rating",
            "vulnerabilities",
            "security_hotspots",
            "security_rating",
            "code_smells",
            "sqale_index",
            "sqale_debt_ratio",
            "coverage",
            "duplicated_lines_density",
            "duplicated_blocks",
            "ncloc",
            "complexity");

    private final Map<String, Set<String>> doneAgents = new ConcurrentHashMap<>();
    private final Map<String, String> sonarProjectKeys = new ConcurrentHashMap<>();
    private final Map<String, String> sonarProjectNames = new ConcurrentHashMap<>();
    private final Set<String> failedRuns = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    protected void setup() {
        AgentDirectory.register(this, "code_analyzer", "code_analyzer");

        addBehaviour(new CyclicBehaviour() {
            public void action() {
                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }

                try {
                    String ontology = msg.getOntology();
                    Map payload = gson.fromJson(msg.getContent(), Map.class);
                    String run_id = String.valueOf(payload.get("run_id"));

                    if (run_id == null || run_id.isBlank() || "null".equals(run_id)) {
                        return;
                    }

                    log(run_id, "RECEIVED", ontology, payload);

                    if (isRunFailed(run_id)) {
                        log(run_id, "IGNORED_AFTER_FAILED", ontology, payload);
                        return;
                    }

                    if (msg.getPerformative() == ACLMessage.INFORM && "START_CODE_ANALYSIS".equals(ontology)) {
                        startCodeAnalysis(payload, run_id);
                        return;
                    }

                    if (msg.getPerformative() == ACLMessage.INFORM && "QA_SUBTASK_FAILED".equals(ontology)) {
                        String reason = String.valueOf(payload.getOrDefault("reason", "subtask_failed"));
                        failedRuns.add(run_id);
                        updateCodeReport(run_id,
                                "code_stage", "FAILED",
                                "code_failed_at", Instant.now().toString(),
                                "reason", reason);
                        sendFailed(run_id, "Static analysis failed: " + reason);
                        doneAgents.remove(run_id);
                        return;
                    }

                    if (msg.getPerformative() == ACLMessage.INFORM && "QA_SUBTASK_DONE".equals(ontology)) {
                        handleSubtaskDone(payload, run_id);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void startCodeAnalysis(Map payload, String run_id) {
        String repo_path = String.valueOf(payload.get("repo_path"));
        String projectKey = stringValue(payload.get("sonar_project_key"),
                System.getenv().getOrDefault("SONAR_PROJECT", "sma-project"));
        String projectName = stringValue(payload.get("sonar_project_name"), projectKey);

        doneAgents.put(run_id, ConcurrentHashMap.newKeySet());
        sonarProjectKeys.put(run_id, projectKey);
        sonarProjectNames.put(run_id, projectName);
        failedRuns.remove(run_id);

        updateCodeReport(run_id,
                "code_stage", "SONAR_RUNNING",
                "code_started_at", Instant.now().toString());

        ACLMessage sonarReq = new ACLMessage(ACLMessage.REQUEST);
        sonarReq.setOntology("RUN_SONAR");
        sonarReq.setContent(gson.toJson(Map.of(
                "run_id", run_id,
                "repo_path", repo_path,
                "sonar_project_key", projectKey,
                "sonar_project_name", projectName)));

        List<AID> sonarAgents = AgentDirectory.find(this, "sonar");
        if (sonarAgents != null && !sonarAgents.isEmpty()) {
            sonarAgents.forEach(sonarReq::addReceiver);
        } else {
            sonarReq.addReceiver(new AID("sonar_agent", AID.ISLOCALNAME));
        }

        send(sonarReq);
        log(run_id, "SENT", "RUN_SONAR", Map.of(
                "repo_path", repo_path,
                "sonar_project_key", projectKey,
                "sonar_project_name", projectName));
    }

    private void handleSubtaskDone(Map payload, String run_id) {
        String agent = String.valueOf(payload.get("agent"));
        boolean ok = Boolean.TRUE.equals(payload.get("ok")) ||
                "true".equalsIgnoreCase(String.valueOf(payload.get("ok")));

        if (!ok) {
            failedRuns.add(run_id);
            sendFailed(run_id, "Agent failed: " + agent);
            return;
        }

        Set<String> done = doneAgents.computeIfAbsent(run_id, k -> ConcurrentHashMap.newKeySet());
        done.add(agent);

        if (!done.containsAll(REQUIRED_AGENTS) || failedRuns.contains(run_id)) {
            return;
        }

        boolean persisted = persistSonarMetrics(run_id);
        if (persisted) {
            updateCodeReport(run_id,
                    "code_stage", "DONE",
                    "code_finished_at", Instant.now().toString());
            notifyCoordinator(run_id, "CODE_ANALYZER_DONE", null);
        } else {
            sendFailed(run_id, "Could not persist SonarQube metrics");
        }

        doneAgents.remove(run_id);
        sonarProjectKeys.remove(run_id);
        sonarProjectNames.remove(run_id);
        failedRuns.remove(run_id);
    }

    private boolean persistSonarMetrics(String run_id) {
        String projectKey = sonarProjectKeys.getOrDefault(run_id,
                System.getenv().getOrDefault("SONAR_PROJECT", "sma-project"));
        String projectName = sonarProjectNames.getOrDefault(run_id, projectKey);
        String baseUrl = System.getenv().getOrDefault("URL_SONAR", "http://sonarqube:9000");

        try {
            String sonarJson = fetchMetrics(baseUrl, projectKey, SONAR_METRICS);

            Document metrics;
            try {
                metrics = Document.parse(sonarJson);
            } catch (Exception e) {
                metrics = new Document("raw", sonarJson);
            }

            Document set = new Document()
                    .append("run_id", run_id)
                    .append("project_key", projectKey)
                    .append("project_name", projectName)
                    .append("metric_keys", SONAR_METRICS)
                    .append("metrics", metrics)
                    .append("stored_at", Instant.now().toString());

            MongoDatabase db = MongoHelper.getDatabase();
            upsert(db.getCollection("code_metrics"), run_id, set);
            upsert(db.getCollection("sonar_metrics"), run_id, set);

            log(run_id, "METRICS_PERSISTED", "SONAR_MEASURES", set);
            return true;

        } catch (Exception ex) {
            log(run_id, "METRICS_FAILED", "SONAR_MEASURES",
                    Map.of("reason", ex.getMessage() == null ? "unknown" : ex.getMessage()));
            return false;
        }
    }

    private String fetchMetrics(String baseUrl, String projectKey, String metricsCsv) throws Exception {
        String url = baseUrl + "/api/measures/component?component=" + urlEncode(projectKey)
                + "&metricKeys=" + urlEncode(metricsCsv);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET();

        String token = System.getenv().getOrDefault("SONAR_TOKEN", "");
        if (!token.isBlank()) {
            String basic = java.util.Base64.getEncoder()
                    .encodeToString((token + ":").getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }

        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        String body = resp.body() == null ? "" : resp.body();
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("Sonar measures API returned status " + resp.statusCode()
                    + " preview=" + preview(body));
        }
        return body;
    }

    private void notifyCoordinator(String run_id, String ontology, String reason) {
        ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
        notify.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
        notify.setOntology(ontology);
        notify.setContent(reason == null
                ? gson.toJson(Map.of("run_id", run_id))
                : gson.toJson(Map.of("run_id", run_id, "reason", reason)));
        send(notify);
        log(run_id, "SENT", ontology, reason == null ? Map.of() : Map.of("reason", reason));
    }

    private void sendFailed(String run_id, String reason) {
        updateCodeReport(run_id,
                "code_stage", "FAILED",
                "code_failed_at", Instant.now().toString(),
                "reason", reason);
        notifyCoordinator(run_id, "CODE_ANALYZER_FAILED", reason);
    }

    private void updateCodeReport(String run_id, Object... kv) {
        try {
            Document set = new Document("run_id", run_id);
            for (int i = 0; i < kv.length; i += 2) {
                set.append(String.valueOf(kv[i]), kv[i + 1]);
            }

            MongoHelper.getDatabase()
                    .getCollection("codeReport")
                    .updateOne(new Document("run_id", run_id),
                            new Document("$set", set),
                            new UpdateOptions().upsert(true));
        } catch (Exception ignored) {}
    }

    private void upsert(MongoCollection<Document> col, String run_id, Document set) {
        col.updateOne(new Document("run_id", run_id),
                new Document("$set", set),
                new UpdateOptions().upsert(true));
    }

    private boolean isRunFailed(String run_id) {
        Document status = MongoHelper.getDatabase()
                .getCollection("runStatus")
                .find(new Document("run_id", run_id))
                .first();

        return status != null &&
                (Boolean.TRUE.equals(status.getBoolean("failed")) || "FAILED".equals(status.getString("stage")));
    }

    private void log(String run_id, String event, String ontology, Object data) {
        try {
            MongoHelper.getDatabase().getCollection("logs").insertOne(new Document()
                    .append("run_id", run_id)
                    .append("agent", "code_analyzer")
                    .append("event", event)
                    .append("ontology", ontology)
                    .append("data", data == null ? new Document() : Document.parse(gson.toJson(data)))
                    .append("created_at", Instant.now().toString()));
        } catch (Exception ignored) {}
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String preview(String body) {
        if (body == null) return "";
        return body.length() > 500 ? body.substring(0, 500) + "..." : body;
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String s = String.valueOf(value);
        if (s.isBlank() || "null".equalsIgnoreCase(s)) return fallback;
        return s;
    }
}
