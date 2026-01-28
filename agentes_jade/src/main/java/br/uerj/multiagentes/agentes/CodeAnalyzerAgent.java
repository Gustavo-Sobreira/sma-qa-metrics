package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;

import org.bson.Document;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;

/**
 * Não remova meus comentários.
 *
 * CodeAnalyzerAgent:
 * - Recebe REPO_READY (run_id, repo_path)
 * - Dispara análises estáticas (por enquanto: Sonar)
 * - Quando todos subagentes concluírem, coleta métricas necessárias
 * - Persiste no Mongo
 * - Envia QA_DONE ao CoordinatorAgent
 *
 * MELHORIAS:
 * - DF para descoberta (tolerante a nomes)
 * - Trata QA_SUBTASK_FAILED e propaga QA_FAILED
 * - Só conclui se ok=true e persistência bem sucedida
 */
public class CodeAnalyzerAgent extends Agent {

    private final Gson gson = GsonProvider.get();
    private final Map<String, Set<String>> doneAgents = new HashMap<>();
    private final Map<String, Boolean> failedRuns = new HashMap<>();

    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    protected void setup() {
        AgentDirectory.register(this, "code-analyzer", "code-analyzer");

        System.out.println("[ 🧪 - CodeAnalyzerAgent ]\n     |->  Pronto.");

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {

                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }

                try {
                    String ontology = msg.getOntology();

                    if (msg.getPerformative() == ACLMessage.INFORM && "REPO_READY".equals(ontology)) {
                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = String.valueOf(payload.get("run_id"));
                        String repoPath = String.valueOf(payload.get("repo_path"));

                        doneAgents.put(runId, new HashSet<>());
                        failedRuns.put(runId, Boolean.FALSE);

                        updateRun(runId, "qa_stage", "SONAR_RUNNING", "qa_started_at", Instant.now().toString());

                        // Dispara Sonar (DF + fallback)
                        ACLMessage sonarReq = new ACLMessage(ACLMessage.REQUEST);
                        sonarReq.setOntology("RUN_SONAR");
                        sonarReq.setContent(gson.toJson(Map.of(
                                "run_id", runId,
                                "repo_path", repoPath
                        )));

                        List<AID> sonarAgents = AgentDirectory.find(myAgent, "sonar");
                        if (sonarAgents != null && !sonarAgents.isEmpty()) {
                            sonarAgents.forEach(sonarReq::addReceiver);
                        } else {
                            // compat com seu setup atual
                            sonarReq.addReceiver(new AID("sonar_agent", AID.ISLOCALNAME));
                        }
                        send(sonarReq);

                        return;
                    }

                    if (msg.getPerformative() == ACLMessage.INFORM && "QA_SUBTASK_FAILED".equals(ontology)) {
                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = String.valueOf(payload.get("run_id"));
                        failedRuns.put(runId, Boolean.TRUE);

                        String reason = String.valueOf(payload.getOrDefault("reason", "subtask failed"));
                        updateRun(runId, "qa_stage", "FAILED", "qa_failed_at", Instant.now().toString());
                        sendQaFailed(runId, "Static analysis failed: " + reason);
                        doneAgents.remove(runId);
                        return;
                    }

                    if (msg.getPerformative() == ACLMessage.INFORM && "QA_SUBTASK_DONE".equals(ontology)) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = String.valueOf(payload.get("run_id"));
                        String agent = String.valueOf(payload.get("agent"));
                        boolean ok = Boolean.TRUE.equals(payload.get("ok")) || "true".equals(String.valueOf(payload.get("ok")));

                        if (!ok) {
                            failedRuns.put(runId, Boolean.TRUE);
                            String reason = String.valueOf(payload.getOrDefault("reason", payload.getOrDefault("error", "unknown")));
                            updateRun(runId, "qa_stage", "FAILED", "qa_failed_at", Instant.now().toString());
                            sendQaFailed(runId, "Static analysis agent returned ok=false: " + agent + " reason=" + reason);
                            doneAgents.remove(runId);
                            return;
                        }

                        Set<String> done = doneAgents.computeIfAbsent(runId, k -> new HashSet<>());
                        done.add(agent);

                        // Quando houver mais agentes, aumente o limiar aqui
                        if (done.size() >= 1 && !failedRuns.getOrDefault(runId, false)) {
                            updateRun(runId, "qa_stage", "PERSISTING", "qa_persist_started_at", Instant.now().toString());

                            boolean persisted = persistSonarMetrics(runId);

                            MongoDatabase db = MongoHelper.getDatabase();
                            MongoCollection<Document> runs = db.getCollection("run_status");
                            runs.updateOne(new Document("run_id", runId),
                                    new Document("$set", new Document("qa_metrics_done", persisted)
                                            .append("qa_metrics_finished_at", Instant.now().toString())
                                            .append("qa_ok", persisted)));

                            if (persisted) {
                                ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                                notify.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
                                notify.setOntology("QA_DONE");
                                notify.setContent(gson.toJson(Map.of(
                                        "run_id", runId,
                                        "ok", true
                                )));
                                send(notify);
                                updateRun(runId, "qa_stage", "DONE", "qa_completed_at", Instant.now().toString());
                            } else {
                                updateRun(runId, "qa_stage", "FAILED", "qa_failed_at", Instant.now().toString());
                                sendQaFailed(runId, "Persistência de métricas Sonar falhou");
                            }

                            doneAgents.remove(runId);
                            failedRuns.remove(runId);
                        }

                        return;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void sendQaFailed(String runId, String reason) {
        ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
        notify.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
        notify.setOntology("QA_FAILED");
        notify.setContent(gson.toJson(Map.of(
                "run_id", runId,
                "reason", reason
        )));
        send(notify);

        System.out.println("[ 🧪 - CodeAnalyzerAgent ]\n     |->  QA_FAILED enviado (run=" + runId + ") reason=" + reason);
    }

    private void updateRun(String runId, String k1, Object v1, String k2, Object v2) {
        try {
            MongoDatabase db = MongoHelper.getDatabase();
            MongoCollection<Document> runs = db.getCollection("run_status");
            runs.updateOne(new Document("run_id", runId),
                    new Document("$set", new Document(k1, v1).append(k2, v2)));
        } catch (Exception ignored) {}
    }

    private boolean persistSonarMetrics(String runId) {
        String projectKey = System.getenv().getOrDefault("SONAR_PROJECT", "sma-project");
        String baseUrl = System.getenv().getOrDefault("URL_SONAR", "http://sonarqube:9000");

        try {
            String metricsList = getAllMetrics(baseUrl);
            String sonarJson = "{}";

            if (metricsList == null || metricsList.isBlank()) {
                System.out.println("[ 🧪 - CodeAnalyzerAgent ]\n     |->  Nenhuma métrica encontrada no Sonar.");
            } else {
                sonarJson = fetchAllMetrics(baseUrl, projectKey, metricsList);
            }

            MongoDatabase db = MongoHelper.getDatabase();
            MongoCollection<Document> coll = db.getCollection("sonar_metrics");

            Document doc = new Document()
                    .append("run_id", runId)
                    .append("project_key", projectKey)
                    .append("stored_at", Instant.now().toString());

            try {
                doc.append("metrics", Document.parse(sonarJson));
            } catch (Exception e) {
                doc.append("metrics_raw", sonarJson);
            }

            coll.insertOne(doc);

            System.out.println("[ 🧪 - CodeAnalyzerAgent ]\n     |->  Métricas Sonar persistidas (run=" + runId + ")");
            return true;

        } catch (Exception ex) {
            System.out.println("[ 🧪 - CodeAnalyzerAgent ]\n     |->  Erro ao persistir métricas do Sonar: " + ex.getMessage());
            ex.printStackTrace();
            return false;
        }
    }

    private String getAllMetrics(String baseUrl) throws Exception {
        String url = baseUrl + "/api/metrics/search?ps=500";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        String token = System.getenv().getOrDefault("SONAR_TOKEN", "");
        if (!token.isBlank()) {
            String auth = token + ":";
            String basic = java.util.Base64.getEncoder()
                    .encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }

        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Sonar metrics API returned status " + resp.statusCode() + " body=" + resp.body());
        }

        Map map = gson.fromJson(resp.body(), Map.class);
        List<Map> metrics = (List<Map>) map.get("metrics");
        if (metrics == null || metrics.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (Map m : metrics) {
            Object key = m.get("key");
            if (key != null) sb.append(key).append(",");
        }

        if (sb.length() == 0) return "";
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private String fetchAllMetrics(String baseUrl, String projectKey, String metricsCsv) throws Exception {
        String url = baseUrl + "/api/measures/component?component=" + urlEncode(projectKey)
                + "&metricKeys=" + urlEncode(metricsCsv);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        String token = System.getenv().getOrDefault("SONAR_TOKEN", "");
        if (!token.isBlank()) {
            String auth = token + ":";
            String basic = java.util.Base64.getEncoder()
                    .encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }

        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Sonar measures API returned status " + resp.statusCode() + " body=" + resp.body());
        }
        return resp.body();
    }

    private String urlEncode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
