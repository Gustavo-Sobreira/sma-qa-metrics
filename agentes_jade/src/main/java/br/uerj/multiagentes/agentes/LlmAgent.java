package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
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
import java.util.List;
import java.util.Map;

public class LlmAgent extends Agent {

    private static final Gson gson = GsonProvider.get();

    private static final boolean LLM_ENABLED =
            Boolean.parseBoolean(System.getenv().getOrDefault("LLM_ENABLED", "true"));

    private static final String GEMINI_API_KEY = System.getenv("GEMINI_API_KEY");
    private static final String GEMINI_MODEL =
            System.getenv().getOrDefault("GEMINI_MODEL", "gemini-3-flash-preview");
    private static final String GEMINI_BASE_URL =
            System.getenv().getOrDefault(
                    "GEMINI_BASE_URL",
                    "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
            );
    private static final long GEMINI_TIMEOUT_SECONDS =
            Long.parseLong(System.getenv().getOrDefault("GEMINI_TIMEOUT_SECONDS", "180"));

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    protected void setup() {
        System.out.println("[ 🤖 - LlmAgent ]\n     |->  Pronto. LLM_ENABLED=" + LLM_ENABLED
                + " | provider=gemini model=" + GEMINI_MODEL);

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchOntology("RUN_LLM");
                ACLMessage msg = myAgent.receive(mt);
                if (msg == null) { block(); return; }

                if (!LLM_ENABLED) {
                    System.out.println("[ 🤖 - LlmAgent ]\n     |->  LLM desabilitado. Ignorando RUN_LLM.");
                    return;
                }

                String runId = null;
                try {
                    Map payload = gson.fromJson(msg.getContent(), Map.class);
                    runId = String.valueOf(payload.get("run_id"));

                    System.out.println("[ 🤖 - LlmAgent ]\n     |->  Iniciando análise (run=" + runId + ")");
                    updateRunStatus(runId, "llm_status", "running", "llm_started_at", Instant.now().toString());

                    Document input = loadInputs(runId);
                    String prompt = buildPrompt(runId, input);

                    String report = callGemini(prompt);

                    saveReport(runId, report);
                    updateRunStatus(runId, "llm_status", "ok", "llm_completed_at", Instant.now().toString());

                    sendLlmDone(runId);

                    System.out.println("[ 🤖 - LlmAgent ]\n     |->  Concluído (run=" + runId + ")");

                } catch (Exception e) {
                    String reason = e.getMessage() == null ? e.toString() : e.getMessage();

                    if (runId != null) {
                        updateRunStatus(runId,
                                "llm_status", "failed",
                                "llm_failed_at", Instant.now().toString(),
                                "llm_error", reason);
                        sendLlmFailed(runId, reason);
                    }

                    System.out.println("[ 🤖 - LlmAgent ]\n     |->  FALHOU (run=" + runId + ") error=" + reason);
                }
            }
        });
    }

    private void sendLlmDone(String runId) {
        ACLMessage done = new ACLMessage(ACLMessage.INFORM);
        done.setOntology("LLM_DONE");
        done.setContent(gson.toJson(Map.of("run_id", runId)));
        done.addReceiver(new AID("coordinator", AID.ISLOCALNAME));
        send(done);
    }

    private void sendLlmFailed(String runId, String reason) {
        ACLMessage fail = new ACLMessage(ACLMessage.INFORM);
        fail.setOntology("LLM_FAILED");
        fail.setContent(gson.toJson(Map.of("run_id", runId, "reason", reason)));
        fail.addReceiver(new AID("coordinator", AID.ISLOCALNAME));
        send(fail);
    }

    private Document loadInputs(String runId) {
        MongoDatabase db = MongoHelper.getDatabase();
        MongoCollection<Document> sonarCol = db.getCollection("sonar_metrics");
        MongoCollection<Document> gitCol = db.getCollection("git_metrics");

        Document sonar = sonarCol.find(new Document("run_id", runId)).first();
        Document git = gitCol.find(new Document("run_id", runId)).first();

        return new Document("run_id", runId)
                .append("sonar", sonar)
                .append("git", git);
    }

    private String buildPrompt(String runId, Document input) {
        return ""
                + "Você é um agente cognitivo (LLMAgent) em uma arquitetura multiagente para análise explicável de qualidade de software.\n"
                + "Gere um relatório textual interpretativo baseado SOMENTE nos dados fornecidos.\n"
                + "Não invente métricas. Se algum dado estiver ausente, diga explicitamente.\n\n"
                + "RUN_ID: " + runId + "\n\n"
                + "DADOS (JSON):\n"
                + input.toJson() + "\n\n"
                + "Saída desejada:\n"
                + "1) Sumário executivo (3-6 bullets)\n"
                + "2) Achados principais (qualidade estática vs evolução do repo)\n"
                + "3) Riscos e hipóteses (com evidências)\n"
                + "4) Recomendações práticas priorizadas\n";
    }

    private String callGemini(String prompt) throws IOException, InterruptedException {
        if (GEMINI_API_KEY == null || GEMINI_API_KEY.isBlank()) {
            throw new IOException("GEMINI_API_KEY não configurada");
        }

        // Gemini aceita o formato "OpenAI chat/completions" via endpoint compatível.
        // docs: Authorization: Bearer <GEMINI_API_KEY> :contentReference[oaicite:1]{index=1}
        Map body = Map.of(
                "model", GEMINI_MODEL,
                "messages", List.of(
                        Map.of("role", "system", "content",
                                "Você é um assistente técnico focado em análise de qualidade de software."),
                        Map.of("role", "user", "content", prompt)
                ),
                "temperature", 0.2
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GEMINI_BASE_URL))
                .timeout(Duration.ofSeconds(GEMINI_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + GEMINI_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Gemini HTTP " + resp.statusCode() + " body=" + preview(resp.body()));
        }

        Map json = gson.fromJson(resp.body(), Map.class);
        List choices = (List) json.get("choices");
        if (choices == null || choices.isEmpty()) return "";
        Map c0 = (Map) choices.get(0);
        Map msg = (Map) c0.get("message");
        Object content = msg == null ? null : msg.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    private void saveReport(String runId, String report) {
        MongoDatabase db = MongoHelper.getDatabase();
        MongoCollection<Document> col = db.getCollection("llm_reports");

        Document doc = new Document()
                .append("run_id", runId)
                .append("created_at", Instant.now().toString())
                .append("provider", "gemini")
                .append("model", GEMINI_MODEL)
                .append("report", report);

        col.insertOne(doc);
    }

    private void updateRunStatus(String runId, Object... kv) {
        MongoDatabase db = MongoHelper.getDatabase();
        MongoCollection<Document> runs = db.getCollection("run_status");

        Document set = new Document();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            set.append(String.valueOf(kv[i]), kv[i + 1]);
        }
        runs.updateOne(new Document("run_id", runId), new Document("$set", set));
    }

    private String preview(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
