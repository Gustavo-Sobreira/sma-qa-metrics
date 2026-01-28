package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import org.bson.Document;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CoordinatorAgent (simplificado + robusto)
 *
 * Fluxo:
 * 1) /webhook recebe repo
 * 2) clona/pull
 * 3) envia REPO_READY para CodeAnalyzer e GitLog
 * 4) aguarda QA_DONE e GIT_DONE (ou falhas)
 * 5) dispara RUN_LLM (apenas uma vez)
 * 6) aguarda LLM_DONE/LLM_FAILED e finaliza run
 *
 * Robustez mantida:
 * - Barra de progresso (logs + Mongo)
 * - Parse tolerante de mensagens (não explode com string crua)
 * - Tratamento de falhas (QA/GIT/LLM)
 * - Evita re-disparo do LLM (llmTriggered)
 * - Descoberta via DF com fallback para nomes fixos
 */
public class CoordinatorAgent extends Agent {

    private static final Gson gson = GsonProvider.get();

    private static final String REPOS_DIR = env("REPOS_DIR", "/repos");
    private static final String PROJECT_DIR_PREFIX = env("PROJECT_DIR_PREFIX", "repo");
    private static final int WEBHOOK_PORT = Integer.parseInt(env("PORT_WEBHOOK", "8090"));
    private static final int TICK_MS = Integer.parseInt(env("PROGRESS_TICK_MS", "2000"));

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    private static class RunState {
        volatile String stage = "IDLE";
        volatile int pct = 0;

        volatile boolean qaOk = false;
        volatile boolean gitOk = false;
        volatile boolean llmStarted = false;
        volatile boolean llmOk = false;

        volatile boolean failed = false;
        volatile boolean llmTriggered = false;

        volatile String lastMsg = "";
        volatile String repoPath = "";
        volatile String repoUrl = "";
    }

    @Override
    protected void setup() {
        AgentDirectory.register(this, "coordinator", "coordinator");
        startHttpServer(WEBHOOK_PORT);

        System.out.println("[ 🧠 - CoordinatorAgent ]"
                + "     |-> REPOS_DIR = " + REPOS_DIR
                + "     |-> Escutando em /webhook (port=" + WEBHOOK_PORT + ")"
                + "     |-> Pronto.");

        // Barra de progresso periódica
        addBehaviour(new TickerBehaviour(this, TICK_MS) {
            @Override
            protected void onTick() {
                for (Map.Entry<String, RunState> e : runs.entrySet()) {
                    String runId = e.getKey();
                    RunState s = e.getValue();
                    if (s == null) continue;
                    if (s.failed) continue;
                    if (s.pct >= 100) continue;

                    System.out.println(renderProgress(runId, s));
                    setRun(runId,
                            "stage", s.stage,
                            "progress_pct", s.pct,
                            "progress_at", Instant.now().toString());
                }
            }
        });

        // Loop de mensagens
        addBehaviour(new CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive(MessageTemplate.MatchPerformative(ACLMessage.INFORM));
                if (msg == null) {
                    block();
                    return;
                }

                String ontology = msg.getOntology();
                String content = msg.getContent();

                Map payload = safeJsonMap(content);
                String runId = getRunId(payload);

                if (runId == null) {
                    System.out.println("[ 🧠 - CoordinatorAgent ] |-> Aviso: msg sem run_id (ontology="
                            + ontology + ") content=" + preview(content));
                    return;
                }

                RunState s = runs.computeIfAbsent(runId, k -> new RunState());

                // --- LLM finalizações ---
                if ("LLM_DONE".equals(ontology)) {
                    s.llmOk = true;
                    s.stage = "LLM_DONE";
                    s.pct = 100;
                    s.lastMsg = "LLM concluído";

                    setRun(runId,
                            "llm_ok", true,
                            "llm_status", "ok",
                            "llm_completed_at", Instant.now().toString(),
                            "stage", s.stage,
                            "progress_pct", s.pct,
                            "progress_at", Instant.now().toString());

                    System.out.println("[ ✅ - RUN " + runId.substring(0, 8) + " ] Finalizado: LLM_DONE");
                    cleanup(runId);
                    return;
                }

                if ("LLM_FAILED".equals(ontology)) {
                    String reason = getReason(payload, content);
                    failRun(runId, s, "LLM_FAILED", reason);
                    return;
                }

                // --- QA / GIT ---
                if ("QA_DONE".equals(ontology)) {
                    s.qaOk = true;
                    s.stage = "QA_DONE";
                    s.pct = Math.max(s.pct, 70);
                    s.lastMsg = "QA concluído";

                    setRun(runId, "qa_ok", true, "qa_completed_at", Instant.now().toString());
                    System.out.println("[ 🧠 - CoordinatorAgent ]     |-> QA_DONE recebido (run=" + runId + ")");
                }

                if ("GIT_DONE".equals(ontology)) {
                    s.gitOk = true;
                    s.stage = "GIT_DONE";
                    s.pct = Math.max(s.pct, 50);
                    s.lastMsg = "Git concluído";

                    setRun(runId, "git_ok", true, "git_completed_at", Instant.now().toString());
                    System.out.println("[ 🧠 - CoordinatorAgent ]     |-> GIT_DONE recebido (run=" + runId + ")");
                }

                if ("QA_FAILED".equals(ontology) || "GIT_FAILED".equals(ontology)) {
                    String reason = getReason(payload, content);
                    failRun(runId, s, ontology, reason);
                    return;
                }

                // --- barreira -> chama LLM uma vez ---
                if (s.qaOk && s.gitOk && !s.failed && !s.llmTriggered) {
                    s.llmTriggered = true;
                    s.stage = "BARRIER_OK";
                    s.pct = Math.max(s.pct, 85);
                    s.lastMsg = "Barreira atingida";

                    System.out.println("[ 🧠 - CoordinatorAgent ]     |-> Barreira atingida: chamando LlmAgent (run=" + runId + ")");
                    triggerLlm(runId, s);
                }
            }
        });
    }

    // ------------------ WEBHOOK ------------------

    private void startHttpServer(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/webhook", new WebhookHandler());
            server.setExecutor(null);
            server.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    class WebhookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            String body = new String(exchange.getRequestBody().readAllBytes());
            JSONObject json = new JSONObject(body);

            String repoUrl = json.getString("repository");

            new File(REPOS_DIR).mkdirs();

            String runId = UUID.randomUUID().toString();
            String repoPath = REPOS_DIR + "/" + PROJECT_DIR_PREFIX + "-" + runId;

            RunState s = runs.computeIfAbsent(runId, k -> new RunState());
            s.repoUrl = repoUrl;
            s.repoPath = repoPath;
            s.stage = "CLONE";
            s.pct = 5;
            s.lastMsg = "Clonando/atualizando repositório...";

            createRunStatus(runId, repoUrl, repoPath);

            String log;
            File repoDir = new File(repoPath);
            if (repoDir.exists() && new File(repoPath + "/.git").exists()) {
                log = runGitPull(repoPath);
            } else {
                deleteDirectory(repoDir);
                repoDir.mkdirs();
                log = runGitClone(repoUrl, repoPath);
            }

            boolean ok = !(log.toLowerCase().contains("fatal") || log.toLowerCase().contains("error"));

            JSONObject response = new JSONObject();
            response.put("run_id", runId);
            response.put("repo_path", repoPath);
            response.put("ok", ok);
            response.put("log", log);

            if (ok) {
                s.stage = "REPO_READY";
                s.pct = 15;
                s.lastMsg = "Repo pronto. Disparando análises...";

                setRun(runId, "git_clone_ok", true, "repo_ready_at", Instant.now().toString());
                sendRepoReady(runId, repoUrl, repoPath);

            } else {
                failRun(runId, s, "GIT_CLONE_FAILED", "git clone/pull failed");
                setRun(runId, "git_clone_ok", false, "failed_at", Instant.now().toString());
            }

            byte[] respBytes = response.toString().getBytes();
            exchange.sendResponseHeaders(200, respBytes.length);
            exchange.getResponseBody().write(respBytes);
            exchange.close();
        }
    }

    private void sendRepoReady(String runId, String repoUrl, String repoPath) {
        Map<String, Object> payload = Map.of(
                "run_id", runId,
                "repo_url", repoUrl,
                "repo_path", repoPath
        );

        // DF + fallback
        sendToService("code-analyzer", new AID("code_analyzer_agent", AID.ISLOCALNAME),
                "REPO_READY", payload);

        sendToService("git-log", new AID("git_log_agent", AID.ISLOCALNAME),
                "REPO_READY", payload);

        RunState s = runs.computeIfAbsent(runId, k -> new RunState());
        s.stage = "ANALYSIS_RUNNING";
        s.pct = 25;
        s.lastMsg = "QA/Git em andamento...";

        System.out.println("[ 🧠 - CoordinatorAgent ]"
                + "     |-> Projeto enviado ao CodeAnalyzerAgent e GitLogAgent."
                + "     |-> run_id=" + runId
                + "     |-> repo_path=" + repoPath);
    }

    private void triggerLlm(String runId, RunState s) {
        if (s.failed) return;

        sendToService("llm", new AID("llm_agent", AID.ISLOCALNAME),
                "RUN_LLM", Map.of("run_id", runId));

        s.llmStarted = true;
        s.stage = "LLM_RUNNING";
        s.pct = Math.max(s.pct, 90);
        s.lastMsg = "LLM analisando...";

        setRun(runId, "llm_started_at", Instant.now().toString());
    }

    private void sendToService(String serviceType, AID fallback, String ontology, Map<String, Object> payload) {
        List<AID> found = AgentDirectory.find(this, serviceType);

        ACLMessage m = new ACLMessage(ACLMessage.INFORM);
        m.setOntology(ontology);
        m.setContent(gson.toJson(payload));

        if (found != null && !found.isEmpty()) {
            for (AID a : found) m.addReceiver(a);
        } else {
            m.addReceiver(fallback);
        }
        send(m);
    }

    // ------------------ FAIL / STATE ------------------

    private void failRun(String runId, RunState s, String type, String reason) {
        s.failed = true;
        s.stage = "FAILED";
        s.pct = 100;
        s.lastMsg = type + " - " + reason;

        setRun(runId,
                "failed", true,
                "failed_type", type,
                "failed_reason", reason,
                "failed_at", Instant.now().toString(),
                "stage", s.stage,
                "progress_pct", s.pct,
                "progress_at", Instant.now().toString());

        System.out.println("[ ❌ - RUN " + runId.substring(0, 8) + " ] Finalizado: " + type + " reason=" + reason);
        cleanup(runId);
    }

    private void cleanup(String runId) {
        // Mantém o RunState no map para logs/inspeção (simples e útil).
        // Se quiser expirar, implemente TTL depois.
    }

    // ------------------ PARSE / HELPERS ------------------

    private static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isBlank()) ? def : v;
    }

    private Map safeJsonMap(String content) {
        if (content == null) return null;
        String s = content.trim();
        if (!s.startsWith("{")) return null;
        try {
            return gson.fromJson(s, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String getRunId(Map payload) {
        if (payload == null) return null;
        Object v = payload.get("run_id");
        if (v == null) return null;
        String s = String.valueOf(v);
        return ("null".equals(s) || s.isBlank()) ? null : s;
    }

    private String getReason(Map payload, String rawContent) {
        if (payload != null && payload.get("reason") != null) return String.valueOf(payload.get("reason"));
        return preview(rawContent);
    }

    private String preview(String s) {
        if (s == null) return "";
        s = s.replace("\n", "\\n");
        return s.length() > 220 ? s.substring(0, 220) + "..." : s;
    }

    private String renderProgress(String runId, RunState s) {
        int width = 24;
        int filled = (s.pct * width) / 100;
        String bar = "[" + "#".repeat(Math.max(0, filled)) + "-".repeat(Math.max(0, width - filled)) + "]";
        String llm = !s.llmStarted ? "pend" : (s.llmOk ? "ok" : "start");

        return String.format("[ ⏳ - RUN %s ] %s %3d%% | stage=%s | QA=%s | GIT=%s | LLM=%s | %s",
                runId.substring(0, 8),
                bar, s.pct, s.stage,
                s.qaOk ? "ok" : "pend",
                s.gitOk ? "ok" : "pend",
                llm,
                s.lastMsg == null ? "" : s.lastMsg);
    }

    private void createRunStatus(String runId, String repoUrl, String repoPath) {
        MongoDatabase db = MongoHelper.getDatabase();
        MongoCollection<Document> runsCol = db.getCollection("run_status");

        Document doc = new Document()
                .append("run_id", runId)
                .append("repo_url", repoUrl)
                .append("repo_path", repoPath)
                .append("created_at", Instant.now().toString())
                .append("git_clone_ok", null)
                .append("qa_ok", null)
                .append("git_ok", null)
                .append("llm_ok", null)
                .append("failed", false)
                .append("stage", "CLONE")
                .append("progress_pct", 0);

        runsCol.insertOne(doc);
    }

    private void setRun(String runId, Object... kv) {
        MongoDatabase db = MongoHelper.getDatabase();
        MongoCollection<Document> runsCol = db.getCollection("run_status");

        Document set = new Document();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            set.append(String.valueOf(kv[i]), kv[i + 1]);
        }

        runsCol.updateOne(new Document("run_id", runId), new Document("$set", set));
    }

    // ------------------ GIT ------------------

    private String runGitClone(String url, String path) {
        return runCommand(new ProcessBuilder("git", "clone", url, path));
    }

    private String runGitPull(String path) {
        return runCommand(new ProcessBuilder("git", "-C", path, "pull"));
    }

    private String runCommand(ProcessBuilder pb) {
        StringBuilder output = new StringBuilder();
        try {
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append("\n");
            }
            process.waitFor();
        } catch (Exception e) {
            output.append(e.getMessage());
        }
        return output.toString();
    }

    private void deleteDirectory(File dir) {
        if (!dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                else f.delete();
            }
        }
        dir.delete();
    }
}
