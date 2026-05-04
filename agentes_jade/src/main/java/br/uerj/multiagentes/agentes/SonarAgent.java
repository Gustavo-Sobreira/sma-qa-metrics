package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;

import org.bson.Document;

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SonarAgent extends Agent {

    private static final Gson gson = GsonProvider.get();
    private static final String WORKER_URL = System.getenv().getOrDefault(
            "SONAR_WORKER_URL",
            "http://sonar-worker:9100/scan");
    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("\"exit_code\"\\s*:\\s*(-?\\d+)");
    private static final Semaphore CONCURRENCY = new Semaphore(
            Integer.parseInt(System.getenv().getOrDefault("SONAR_MAX_CONCURRENCY", "1")));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(200))
            .build();

    @Override
    protected void setup() {
        AgentDirectory.register(this, "sonar", "sonar");
        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }

                if (msg.getPerformative() != ACLMessage.REQUEST || !"RUN_SONAR".equals(msg.getOntology())) {
                    return;
                }

                Map payload = gson.fromJson(msg.getContent(), Map.class);
                String run_id = String.valueOf(payload.get("run_id"));
                String repo_path = String.valueOf(payload.get("repo_path"));
                String sonar_project_key = stringValue(payload.get("sonar_project_key"),
                        System.getenv().getOrDefault("SONAR_PROJECT", "sma-project"));
                String sonar_project_name = stringValue(payload.get("sonar_project_name"), sonar_project_key);

                log(run_id, "RECEIVED", "RUN_SONAR", payload);

                if (isRunFailed(run_id)) {
                    log(run_id, "IGNORED_AFTER_FAILED", "RUN_SONAR", payload);
                    return;
                }

                boolean acquired = false;

                try {
                    CONCURRENCY.acquire();
                    acquired = true;
                    log(run_id, "CONCURRENCY_ACQUIRED", "RUN_SONAR", Map.of("available_permits", CONCURRENCY.availablePermits()));

                    String reqBody = gson.toJson(Map.of(
                            "repo_path", repo_path,
                            "run_id", run_id,
                            "sonar_project_key", sonar_project_key,
                            "sonar_project_name", sonar_project_name));

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(WORKER_URL))
                            .timeout(Duration.ofSeconds(Long.parseLong(
                                    System.getenv().getOrDefault("SONAR_WORKER_TIMEOUT_SECONDS", "3000"))))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                            .build();

                    HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                    String body = resp.body() == null ? "" : resp.body();
                    saveArtifact(repo_path, body);
                    savePostgres(run_id, repo_path, resp.statusCode(), body, null);

                    boolean httpOk = resp.statusCode() >= 200 && resp.statusCode() < 300;
                    if (!httpOk) {
                        failToCodeAnalyzer(run_id, "sonar",
                                "worker http status=" + resp.statusCode() + " preview=" + preview(body));
                        return;
                    }

                    Map worker;
                    try {
                        worker = gson.fromJson(body, Map.class);
                    } catch (Exception parseEx) {
                        failToCodeAnalyzer(run_id, "sonar",
                                "worker response is not JSON (http=" + resp.statusCode() + ") preview=" + preview(body));
                        return;
                    }

                    boolean ok = Boolean.TRUE.equals(worker.get("ok")) ||
                            "true".equalsIgnoreCase(String.valueOf(worker.get("ok")));
                    Integer exitCode = extractExitCode(worker, body);

                    if (ok && exitCode == null) {
                        failToCodeAnalyzer(run_id, "sonar",
                                "worker ok=true but could not parse exit_code preview=" + preview(body));
                        return;
                    }

                    int exit = exitCode == null ? -999 : exitCode;
                    if (!(ok && exit == 0)) {
                        failToCodeAnalyzer(run_id, "sonar",
                                "worker failed (http=" + resp.statusCode() + ", ok=" + ok + ", exit_code=" + exit
                                        + ") preview=" + preview(body));
                        return;
                    }

                    ACLMessage doneMsg = new ACLMessage(ACLMessage.INFORM);
                    doneMsg.addReceiver(new AID("code_analyzer_agent", AID.ISLOCALNAME));
                    doneMsg.setOntology("QA_SUBTASK_DONE");
                    doneMsg.setContent(gson.toJson(Map.of(
                            "run_id", run_id,
                            "agent", "sonar",
                            "ok", true,
                            "status", resp.statusCode(),
                            "exit_code", exit,
                            "sonar_project_key", sonar_project_key,
                            "sonar_project_name", sonar_project_name)));
                    send(doneMsg);
                    log(run_id, "SENT", "QA_SUBTASK_DONE", Map.of("status", resp.statusCode(), "exit_code", exit));

                } catch (Exception e) {
                    savePostgres(run_id, repo_path, -1, "", e.getMessage());
                    failToCodeAnalyzer(run_id, "sonar", e.getMessage() == null ? "sonar worker error" : e.getMessage());
                } finally {
                    if (acquired) {
                        CONCURRENCY.release();
                        log(run_id, "CONCURRENCY_RELEASED", "RUN_SONAR", Map.of("available_permits", CONCURRENCY.availablePermits()));
                    }
                }
            }
        });
    }

    private Integer extractExitCode(Map worker, String body) {
        Integer parsed = parseIntLoose(worker.get("exit_code"));
        if (parsed != null) return parsed;

        parsed = parseIntLoose(worker.get("exitCode"));
        if (parsed != null) return parsed;

        Matcher m = EXIT_CODE_PATTERN.matcher(body == null ? "" : body);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Integer parseIntLoose(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private void failToCodeAnalyzer(String run_id, String agent, String reason) {
        ACLMessage failMsg = new ACLMessage(ACLMessage.INFORM);
        failMsg.addReceiver(new AID("code_analyzer_agent", AID.ISLOCALNAME));
        failMsg.setOntology("QA_SUBTASK_FAILED");
        failMsg.setContent(gson.toJson(Map.of(
                "run_id", run_id,
                "agent", agent,
                "reason", reason == null ? "unknown" : reason)));
        send(failMsg);
        log(run_id, "SENT", "QA_SUBTASK_FAILED", Map.of("agent", agent, "reason", reason == null ? "unknown" : reason));
    }

    private void saveArtifact(String repo_path, String body) {
        try {
            File dir = new File(repo_path, ".sma/sonar");
            dir.mkdirs();

            File out = new File(dir, "scan-" + Instant.now().toEpochMilli() + ".json");
            try (FileWriter fw = new FileWriter(out)) {
                fw.write(body == null ? "" : body);
            }
        } catch (Exception ignored) {}
    }

    private void savePostgres(String run_id, String repo_path, int httpStatus, String body, String error) {
        String url = System.getenv().getOrDefault("URI_SONAR_JDBC", "");
        if (url.isBlank()) {
            return;
        }

        String user = System.getenv().getOrDefault("SONAR_JDBC_USERNAME", System.getenv().getOrDefault("POSTGRES_USER", ""));
        String password = System.getenv().getOrDefault("SONAR_JDBC_PASSWORD", System.getenv().getOrDefault("POSTGRES_PASSWORD", ""));

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("""
                        create table if not exists sonar_worker_runs (
                            id bigserial primary key,
                            run_id text not null,
                            repo_path text,
                            http_status integer,
                            response_body text,
                            error text,
                            created_at timestamptz default now()
                        )
                        """);
            }

            try (PreparedStatement ps = conn.prepareStatement("""
                    insert into sonar_worker_runs(run_id, repo_path, http_status, response_body, error)
                    values (?, ?, ?, ?, ?)
                    """)) {
                ps.setString(1, run_id);
                ps.setString(2, repo_path);
                ps.setInt(3, httpStatus);
                ps.setString(4, body);
                ps.setString(5, error);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log(run_id, "POSTGRES_SAVE_FAILED", "RUN_SONAR",
                    Map.of("reason", e.getMessage() == null ? "unknown" : e.getMessage()));
        }
    }

    private boolean isRunFailed(String run_id) {
        try {
            Document status = MongoHelper.getDatabase()
                    .getCollection("runStatus")
                    .find(new Document("run_id", run_id))
                    .first();

            return status != null &&
                    (Boolean.TRUE.equals(status.getBoolean("failed")) || "FAILED".equals(status.getString("stage")));
        } catch (Exception e) {
            return false;
        }
    }

    private void log(String run_id, String event, String ontology, Object data) {
        try {
            MongoHelper.getDatabase().getCollection("logs").insertOne(new Document()
                    .append("run_id", run_id)
                    .append("agent", "sonar")
                    .append("event", event)
                    .append("ontology", ontology)
                    .append("data", data == null ? new Document() : Document.parse(gson.toJson(data)))
                    .append("created_at", Instant.now().toString()));
        } catch (Exception ignored) {}
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
