package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import org.bson.Document;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;

public class LlmAgent extends Agent {

    private static final Gson gson = GsonProvider.get();

    private static final boolean LLM_ENABLED = Boolean
            .parseBoolean(System.getenv().getOrDefault("LLM_ENABLED", "true"));

    private static final String API_KEY = System.getenv("GEMINI_API_KEY");
    private static final String MODEL = System.getenv().getOrDefault("GEMINI_MODEL", "gemini-2.5-flash");

    private static final String BASE_URL = System.getenv().getOrDefault("GEMINI_BASE_URL",
            "https://generativelanguage.googleapis.com/v1beta/models/");
    private static final Semaphore CONCURRENCY = new Semaphore(
            Integer.parseInt(System.getenv().getOrDefault("LLM_MAX_CONCURRENCY", "1")));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    protected void setup() {

        addBehaviour(new CyclicBehaviour() {
            public void action() {

                ACLMessage msg = receive(MessageTemplate.MatchOntology("RUN_LLM"));

                if (msg == null) {
                    block();
                    return;
                }

                String run_id = null;
                boolean acquired = false;

                try {

                    Map<String, Object> payload = gson.fromJson(msg.getContent(), Map.class);
                    run_id = String.valueOf(payload.get("run_id"));
                    log(run_id, "RECEIVED", "RUN_LLM", payload);

                    if (!LLM_ENABLED) {
                        updateStatus(run_id, "disabled", "LLM_ENABLED=false");
                        notifyFail(run_id, "LLM_ENABLED=false");
                        return;
                    }

                    if (isRunFailed(run_id)) {
                        log(run_id, "IGNORED_AFTER_FAILED", "RUN_LLM", payload);
                        return;
                    }

                    updateStatus(run_id, "running");
                    CONCURRENCY.acquire();
                    acquired = true;
                    log(run_id, "CONCURRENCY_ACQUIRED", "RUN_LLM", Map.of("available_permits", CONCURRENCY.availablePermits()));

                    Document raw = loadRaw(run_id);
                    Document unified = buildUnifiedInput(raw);

                    String prompt = buildPrompt(unified);
                    String report = callLLM(prompt);

                    validate(report, unified);

                    persistReport(run_id, report);

                    updateStatus(run_id, "ok");

                    notifyDone(run_id);

                } catch (Exception e) {

                    String reason = e.getMessage();

                    updateStatus(run_id, "failed", reason);

                    notifyFail(run_id, reason);
                } finally {
                    if (acquired) {
                        CONCURRENCY.release();
                        log(run_id, "CONCURRENCY_RELEASED", "RUN_LLM", Map.of("available_permits", CONCURRENCY.availablePermits()));
                    }
                }
            }
        });
    }


    private Document loadRaw(String run_id) {

        MongoDatabase db = MongoHelper.getDatabase();

        return new Document("run_id", run_id)
                .append("code_metrics", db.getCollection("code_metrics")
                        .find(new Document("run_id", run_id)).first())
                .append("project", db.getCollection("projectsReport")
                        .find(new Document("run_id", run_id)).first())
                .append("runStatus", db.getCollection("runStatus")
                        .find(new Document("run_id", run_id)).first())
                .append("codeReport", db.getCollection("codeReport")
                        .find(new Document("run_id", run_id)).first());
    }


    private Document buildUnifiedInput(Document raw) {

        Document unified = new Document();

        unified.append("code_metrics", extractCodeMetrics(raw.get("code_metrics", Document.class)));
        unified.append("project", extractProject(raw.get("project", Document.class)));
        unified.append("pipeline", extractPipeline(raw.get("runStatus", Document.class), raw.get("codeReport", Document.class)));

        return unified;
    }

    private Object extractCodeMetrics(Document codeMetrics) {
        if (codeMetrics == null)
            return "NOT AVAILABLE";

        Object m = codeMetrics.get("metrics");
        return m != null ? m : "NOT AVAILABLE";
    }

    private Object extractProject(Document project) {
        if (project == null)
            return "NOT AVAILABLE";

        if (project.containsKey("git_log_metrics"))
            return project.get("git_log_metrics");

        if (project.containsKey("metrics"))
            return project.get("metrics");

        if (project.containsKey("data"))
            return project.get("data");

        return "NOT AVAILABLE";
    }

    private Object extractPipeline(Document runStatus, Document codeReport) {
        if (runStatus == null && codeReport == null)
            return "NOT AVAILABLE";

        return new Document()
                .append("stage", runStatus == null ? null : runStatus.get("stage"))
                .append("progress", runStatus == null ? null : runStatus.get("progress_pct"))
                .append("status_flags", new Document()
                        .append("git_ok", runStatus == null ? null : runStatus.get("git_ok"))
                        .append("code_ok", runStatus == null ? null : runStatus.get("code_ok"))
                        .append("project_ok", runStatus == null ? null : runStatus.get("project_ok"))
                        .append("llm_ok", runStatus == null ? null : runStatus.get("llm_ok")))
                .append("code_report", codeReport);
    }


    private String buildPrompt(Document input) {
        return """
                ROLE:
                You are a STRICT analytical agent.

                CONSTRAINTS:
                - Use ONLY the provided JSON data
                - Do NOT introduce new numerical values
                - Do NOT use prior knowledge

                NUMERICAL CONSISTENCY RULE:
                - Every number in your response MUST exist in the JSON
                - If not present → DO NOT use it

                MISSING DATA RULES:
                - Missing metric → "NOT AVAILABLE"
                - Empty Sonar measures → ALL code metrics = "NOT AVAILABLE"
                - Do NOT estimate or infer missing values

                PROHIBITED ACTIONS:
                - Invent numbers
                - Use external knowledge
                - Generalize beyond JSON

                SELF-VALIDATION:
                Before answering, verify:
                1. No new numbers were introduced
                2. All missing values are "NOT AVAILABLE"
                3. Output format is strictly followed

                FAIL CONDITIONS:
                If any rule is violated, output EXACTLY:
                ERROR: INSUFFICIENT DATA

                DATA (JSON):
                <<<START_JSON>>>
                """
                + input.toJson() +
                """
                        <<<END_JSON>>>

                        OUTPUT FORMAT (STRICT):

                        1) Executive Summary
                        - Exactly 3 bullet points

                        2) Key Findings
                        - Bullet list only

                        3) Project Metrics
                        - Metric: Value

                        4) Code Metrics
                        - Metric: Value

                        5) Risks and Hypotheses
                        - Risk + evidence from JSON

                        6) Prioritized Practical Recommendations
                        - [Priority: High | Medium | Low] Recommendation
                        """;
    }

    private String callLLM(String prompt) throws Exception {
        if (API_KEY == null || API_KEY.isBlank()) {
            throw new IOException("GEMINI_API_KEY is not configured");
        }

        String url = BASE_URL + MODEL + ":generateContent?key=" + API_KEY;

        Map<String, Object> requestBody = Map.of(
                "contents", List.of(
                        Map.of("role", "user",
                                "parts", List.of(Map.of("text", prompt)))));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        String responseBody = resp.body() == null ? "" : resp.body();

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Gemini returned status " + resp.statusCode() + " preview=" + preview(responseBody));
        }

        Map json = gson.fromJson(responseBody, Map.class);

        List<Map> candidates = (List<Map>) json.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new IOException("Gemini response has no candidates preview=" + preview(responseBody));
        }

        Map content = (Map) candidates.get(0).get("content");
        List<Map> parts = (List<Map>) content.get("parts");

        return String.valueOf(parts.get(0).get("text"));
    }


    private void validate(String report, Document input) throws IOException {

        if (report == null || report.isBlank())
            throw new IOException("Empty LLM response");

        if (report.contains("ERROR: INSUFFICIENT DATA"))
            throw new IOException("Controlled failure");

        boolean hasSonar = input.get("code_metrics") instanceof Document;

        if (!hasSonar && report.matches(".*\\d+.*"))
            throw new IOException("Hallucination detected");
    }


    private void persistReport(String run_id, String report) {

        MongoHelper.getDatabase()
                .getCollection("llm_reports")
                .updateOne(new Document("run_id", run_id),
                        new Document("$set", new Document()
                                .append("run_id", run_id)
                                .append("updated_at", Instant.now().toString())
                                .append("report", report)),
                        new UpdateOptions().upsert(true));
        log(run_id, "REPORT_PERSISTED", "RUN_LLM", Map.of("length", report.length()));
    }


    private void updateStatus(String run_id, String status) {
        updateStatus(run_id, status, null);
    }

    private void updateStatus(String run_id, String status, String error) {

        Document set = new Document()
                .append("llm_status", status)
                .append("updated_at", Instant.now().toString());

        if (error != null)
            set.append("error", error);

        MongoHelper.getDatabase()
                .getCollection("runStatus")
                .updateOne(new Document("run_id", run_id),
                        new Document("$set", set),
                        new UpdateOptions().upsert(true));
    }

    private void notifyDone(String run_id) {

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("LLM_DONE");
        msg.setContent(gson.toJson(Map.of("run_id", run_id)));
        msg.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
        send(msg);
        log(run_id, "SENT", "LLM_DONE", Map.of());
    }

    private void notifyFail(String run_id, String reason) {

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology("LLM_FAILED");
        msg.setContent(gson.toJson(Map.of("run_id", run_id, "reason", reason == null ? "unknown" : reason)));
        msg.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
        send(msg);
        log(run_id, "SENT", "LLM_FAILED", Map.of("reason", reason == null ? "unknown" : reason));
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
                    .append("agent", "llm")
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
}
