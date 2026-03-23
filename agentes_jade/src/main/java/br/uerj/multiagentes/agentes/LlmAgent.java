package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoDatabase;

import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import org.bson.Document;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class LlmAgent extends Agent {

    private static final Gson gson = GsonProvider.get();

    private static final boolean LLM_ENABLED = Boolean
            .parseBoolean(System.getenv().getOrDefault("LLM_ENABLED", "true"));

    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");

    private static final String GEMINI_MODEL = System.getenv()
            .getOrDefault("GEMINI_MODEL", "gemini-2.5-flash");

    private static final String GEMINI_BASE_URL = System.getenv()
            .getOrDefault("GEMINI_BASE_URL",
                    "https://generativelanguage.googleapis.com/v1beta/models/");

    private static final long GEMINI_TIMEOUT_SECONDS = Long
            .parseLong(System.getenv().getOrDefault("GEMINI_TIMEOUT_SECONDS", "120"));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    protected void setup() {
        System.out.println("[ LlmAgent ] - Pronto. Model=" + GEMINI_BASE_URL + GEMINI_MODEL);

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {

                ACLMessage msg = myAgent.receive(
                        MessageTemplate.MatchOntology("RUN_LLM"));

                if (msg == null) {
                    block();
                    return;
                }

                if (!LLM_ENABLED) {
                    System.out.println("[ LlmAgent ] - LLM desabilitado.");
                    return;
                }

                String runId = null;

                try {
                    Map<String, Object> payload = gson.fromJson(msg.getContent(), Map.class);
                    runId = String.valueOf(payload.get("run_id"));

                    updateRunStatus(runId,
                            "llm_status", "running",
                            "llm_started_at", Instant.now().toString());

                    Document input = loadInputs(runId);
                    Document enriched = enrichMetrics(input);
                    String prompt = buildPrompt(runId, enriched);
                    String report = callGeminiWithRetry(prompt);
                    
                    validateReport(report, enriched);
                    saveReport(runId, report);

                    updateRunStatus(runId,
                            "llm_status", "ok",
                            "llm_completed_at", Instant.now().toString());

                    sendLlmDone(runId);

                } catch (Exception e) {

                    String reason = e.getMessage() == null ? e.toString() : e.getMessage();

                    if (runId != null) {
                        updateRunStatus(runId,
                                "llm_status", "failed",
                                "llm_failed_at", Instant.now().toString(),
                                "llm_error", reason);

                        sendLlmFailed(runId, reason);
                    }

                    System.out.println("[ LlmAgent ] - FAIL (run=" + runId + ") error=" + reason);
                }
            }
        });
    }


    private String callGeminiWithRetry(String prompt) throws Exception {
        int attempts = 3;

        for (int i = 0; i < attempts; i++) {
            try {
                return callGemini(prompt);
            } catch (Exception e) {
                if (i == attempts - 1)
                    throw e;
                Thread.sleep(2000);
            }
        }
        return "";
    }

    private String callGemini(String prompt) throws IOException, InterruptedException {

        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            throw new IOException("GEMINI_API_KEY não configurada");
        }

        String url = GEMINI_BASE_URL
                + GEMINI_MODEL
                + ":generateContent?key="
                + GEMINI_API_KEY;

        Map<String, Object> body = Map.of(
                "contents", List.of(
                        Map.of(
                                "role", "user",
                                "parts", List.of(
                                        Map.of("text", prompt)))),
                "generationConfig", Map.of(
                        "temperature", 0.2));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(GEMINI_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        System.out.println("[ LLM RAW ] " + preview(resp.body()));

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Gemini HTTP " + resp.statusCode()
                    + " body=" + preview(resp.body()));
        }

        Map<String, Object> json = gson.fromJson(resp.body(), Map.class);

        List<Map> candidates = (List<Map>) json.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new IOException("Resposta sem candidates");
        }

        Map first = candidates.get(0);

        Map content = (Map) first.get("content");
        if (content == null) {
            throw new IOException("Resposta sem content");
        }

        List<Map> parts = (List<Map>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            throw new IOException("Resposta sem parts");
        }

        Object text = parts.get(0).get("text");

        return text == null ? "" : String.valueOf(text);
    }

    private void validateReport(String report, Document input) throws IOException {

        if (report == null || report.isBlank()) {
            throw new IOException("LLM retornou vazio");
        }

        if (report.contains("ERROR: INSUFFICIENT DATA")) {
            throw new IOException("Dados insuficientes - resposta válida controlada");
        }

        Document sonar = input.get("sonar", Document.class);
        boolean hasMeasures = false;

        if (sonar != null) {
            Document metrics = sonar.get("metrics", Document.class);
            if (metrics != null) {
                Document component = metrics.get("component", Document.class);
                if (component != null) {
                    List measures = (List) component.get("measures");
                    hasMeasures = measures != null && !measures.isEmpty();
                }
            }
        }

        if (!hasMeasures) {
            if (report.matches(".*\\d+.*")) {
                throw new IOException("ALUCINAÇÃO DETECTADA: números sem dados Sonar");
            }
        }
    }



    private void sendLlmDone(String runId) {
        ACLMessage done = new ACLMessage(ACLMessage.INFORM);
        done.setOntology("LLM_DONE");
        done.setContent(gson.toJson(Map.of("run_id", runId)));
        done.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
        send(done);
    }

    private void sendLlmFailed(String runId, String reason) {
        ACLMessage fail = new ACLMessage(ACLMessage.INFORM);
        fail.setOntology("LLM_FAILED");
        fail.setContent(gson.toJson(Map.of("run_id", runId, "reason", reason)));
        fail.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
        send(fail);
    }

    private Document loadInputs(String runId) {
        MongoDatabase db = MongoHelper.getDatabase();

        Document sonar = db.getCollection("sonar_metrics")
                .find(new Document("run_id", runId)).first();

        Document git = db.getCollection("git_metrics")
                .find(new Document("run_id", runId)).first();

        return new Document("run_id", runId)
                .append("sonar", sonar)
                .append("git", git);
    }

    private String buildPrompt(String runId, Document input) {
        return ""
                + "You are a STRICT analytical agent.\n"
                + "You MUST ONLY use the provided JSON data.\n"
                + "You MAY derive insights, comparisons, and qualitative analysis\n"
                + "FROM the provided data, but you MUST NOT introduce new numerical values.\n\n"

                + "CRITICAL RULES:\n"
                + "- If a metric is missing → explicitly write: NOT AVAILABLE\n"
                + "- If Sonar measures array is empty → ALL code metrics MUST be NOT AVAILABLE\n"
                + "- If you output any number not present in JSON → response is INVALID\n"
                + "- DO NOT use prior knowledge\n"
                

                + "FAIL CONDITION:\n"
                + "If you cannot comply, output EXACTLY:\n"
                + "ERROR: INSUFFICIENT DATA\n\n"

                + "DATA (JSON):\n"
                + input.toJson() + "\n\n"

                + "OUTPUT FORMAT (STRICT):\n"

                + "1) Executive Summary\n"
                + "- Exactly 3 bullet points\n\n"

                + "2) Key Findings\n\n"

                + "3) Project Metrics\n\n"

                + "4) Code Metrics\n\n"

                + "5) Risks and Hypotheses\n\n"

                + "6) Prioritized Practical Recommendations\n";
    }

    private void saveReport(String runId, String report) {
        MongoDatabase db = MongoHelper.getDatabase();

        db.getCollection("llm_reports").insertOne(
                new Document()
                        .append("run_id", runId)
                        .append("created_at", Instant.now().toString())
                        .append("provider", "gemini")
                        .append("model", GEMINI_MODEL)
                        .append("report", report));
    }

    private void updateRunStatus(String runId, Object... kv) {
        MongoDatabase db = MongoHelper.getDatabase();

        Document set = new Document();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            set.append(String.valueOf(kv[i]), kv[i + 1]);
        }

        db.getCollection("run_status")
                .updateOne(new Document("run_id", runId),
                        new Document("$set", set));
    }

    private String preview(String s) {
        if (s == null)
            return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }


    private Document enrichMetrics(Document input) {

        Document git = input.get("git", Document.class);
        if (git == null)
            git = new Document();

        Document enriched = new Document(input);

        Document locMetrics = git.get("loc_metrics", Document.class);

        List<Document> commits = locMetrics != null
                ? (List<Document>) locMetrics.getOrDefault("commits", Collections.emptyList())
                : Collections.emptyList();

        int totalLOC = 0;
        int totalNALOC = 0;

        for (Document c : commits) {
            totalLOC += c.getInteger("LOC", 0);
            totalNALOC += c.getInteger("NALOC", 0);
        }

        enriched.append("project_metrics",
                new Document()
                        .append("total_loc", totalLOC)
                        .append("total_naloc", totalNALOC)
                        .append("total_commits", commits.size()));

        return enriched;
    }
}