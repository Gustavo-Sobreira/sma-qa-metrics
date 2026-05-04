package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;

import com.sun.net.httpserver.*;

import jade.core.*;
import jade.core.behaviours.*;
import jade.lang.acl.*;

import org.bson.Document;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class CoordinatorAgent extends Agent {

    private static final Gson gson = GsonProvider.get();
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT_WEBHOOK", "8080"));
    private static final Set<String> PIPELINE_ONTOLOGIES = Set.of(
            "GIT_DONE",
            "GIT_FAILED",
            "CODE_ANALYZER_DONE",
            "CODE_ANALYZER_FAILED",
            "PROJECT_ANALYZER_DONE",
            "PROJECT_ANALYZER_FAILED",
            "LLM_DONE",
            "LLM_FAILED");

    private final Map<String, RunState> runs = new ConcurrentHashMap<>();

    private static class RunState {
        String stage = "START";
        int pct = 0;

        boolean git = false;
        boolean code = false;
        boolean project = false;
        boolean llm = false;

        boolean failed = false;
        boolean llmTriggered = false;

        String repoUrl;
        String repoPath;
    }

    @Override
    protected void setup() {

        System.out.println("[Coordinator] Agent iniciado.");

        AgentDirectory.register(this, "coordinator_agent", "coordinator_agent");
        System.out.println("[Coordinator] Registrado no AgentDirectory como type=coordinator.");

        startHttpServer(PORT);
        System.out.println("[Coordinator] Webhook HTTP ativo na porta: " + PORT);

        addBehaviour(new CyclicBehaviour() {
            public void action() {

                ACLMessage msg = receive(MessageTemplate.MatchPerformative(ACLMessage.INFORM));

                if (msg == null) {
                    block();
                    return;
                }

                System.out.println("[Coordinator] Mensagem JADE recebida.");
                System.out.println("[Coordinator] Ontology: " + msg.getOntology());
                System.out.println("[Coordinator] Sender: " + (msg.getSender() == null ? "UNKNOWN" : msg.getSender().getLocalName()));
                System.out.println("[Coordinator] Content: " + msg.getContent());

                String ontology = msg.getOntology();
                if (!PIPELINE_ONTOLOGIES.contains(ontology)) {
                    System.out.println("[Coordinator] Mensagem ignorada: ontology fora do pipeline: " + ontology);
                    return;
                }

                Map payload;
                try {
                    payload = gson.fromJson(msg.getContent(), Map.class);
                } catch (Exception parseError) {
                    System.out.println("[Coordinator] Mensagem ignorada: conteúdo não-JSON para ontology="
                            + ontology + " erro=" + parseError.getMessage());
                    return;
                }

                String run_id = String.valueOf(payload.get("run_id"));

                if (run_id == null || run_id.isBlank() || "null".equals(run_id)) {
                    System.out.println("[Coordinator] Mensagem ignorada: run_id ausente ou inválido.");
                    return;
                }

                rt(run_id, "Mensagem recebida -> ontology=" + ontology);
                rt(run_id, "Payload recebido -> " + payload);

                RunState s = runs.computeIfAbsent(run_id, k -> new RunState());
                log(run_id, "coordinator", "RECEIVED", ontology, payload);

                if (s.failed && !"LLM_FAILED".equals(ontology)) {
                    rt(run_id, "Mensagem tardia ignorada porque o pipeline já falhou.");
                    log(run_id, "coordinator", "IGNORED_LATE_MESSAGE", ontology, payload);
                    return;
                }

                switch (ontology) {

                    case "GIT_DONE":
                        if (isFailed(run_id, s))
                            return;

                        s.git = true;
                        s.stage = "GIT_DONE";
                        s.pct = 20;
                        s.repoPath = String.valueOf(payload.get("repo_path"));

                        rt(run_id, "GIT_DONE recebido. Clone finalizado.");
                        rt(run_id, "Repo path definido como: " + s.repoPath);
                        rt(run_id, "Progresso atualizado: 20%");
                        rt(run_id, "Disparando análises paralelas: CodeAnalyzer + ProjectAnalyzer.");

                        update(run_id, "git_ok", true);

                        sendTo("code_analyzer", "START_CODE_ANALYSIS", payload);
                        sendTo("project_analyzer", "START_PROJECT_ANALYSIS", payload);

                        break;

                    case "GIT_FAILED":
                        rt(run_id, "GIT_FAILED recebido.");
                        fail(run_id, "GIT_FAILED", payload);
                        return;

                    case "CODE_ANALYZER_DONE":
                        if (isFailed(run_id, s))
                            return;

                        s.code = true;
                        s.stage = "CODE_DONE";
                        s.pct += 35;

                        rt(run_id, "CODE_ANALYZER_DONE recebido. Análise de código finalizada.");
                        rt(run_id, "Estado atual -> code=true, project=" + s.project + ", llmTriggered=" + s.llmTriggered);
                        rt(run_id, "Progresso atualizado: " + s.pct + "%");

                        update(run_id, "code_ok", true);

                        break;

                    case "PROJECT_ANALYZER_DONE":
                        if (isFailed(run_id, s))
                            return;

                        s.project = true;
                        s.stage = "PROJECT_DONE";
                        s.pct += 35;

                        rt(run_id, "PROJECT_ANALYZER_DONE recebido. Análise de projeto finalizada.");
                        rt(run_id, "Estado atual -> code=" + s.code + ", project=true, llmTriggered=" + s.llmTriggered);
                        rt(run_id, "Progresso atualizado: " + s.pct + "%");

                        update(run_id, "project_ok", true);

                        break;

                    case "CODE_ANALYZER_FAILED":
                    case "PROJECT_ANALYZER_FAILED":
                        rt(run_id, ontology + " recebido.");
                        fail(run_id, ontology, payload);
                        return;

                    case "LLM_DONE":
                        if (isFailed(run_id, s))
                            return;

                        s.llm = true;
                        s.stage = "DONE";
                        s.pct = 100;

                        rt(run_id, "LLM_DONE recebido. Execução LLM concluída.");
                        rt(run_id, "Pipeline finalizado com sucesso.");
                        rt(run_id, "Progresso atualizado: 100%");

                        update(run_id,
                                "llm_ok", true,
                                "stage", "DONE",
                                "progress_pct", 100,
                                "finished_at", Instant.now().toString());

                        return;

                    case "LLM_FAILED":
                        rt(run_id, "LLM_FAILED recebido.");
                        fail(run_id, "LLM_FAILED", payload);
                        return;

                    default:
                        rt(run_id, "Ontology não tratada: " + ontology);
                        break;
                }

                if (s.code && s.project && !s.llmTriggered && !s.failed) {
                    rt(run_id, "Pré-condições para LLM satisfeitas: code=true e project=true.");
                    rt(run_id, "Disparando LlmAgent.");

                    s.llmTriggered = true;
                    s.stage = "LLM_RUNNING";
                    s.pct = 90;

                    update(run_id, "stage", "LLM_RUNNING", "progress_pct", 90);

                    sendTo("llm", "RUN_LLM", Map.of("run_id", run_id));
                }

                update(run_id,
                        "stage", s.stage,
                        "progress_pct", s.pct,
                        "updated_at", Instant.now().toString());

                rt(run_id, "Status persistido -> stage=" + s.stage + ", progress_pct=" + s.pct);
            }
        });
    }

    private void startHttpServer(int port) {
        try {
            System.out.println("[Coordinator] Inicializando servidor HTTP...");
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/webhook", new WebhookHandler());
            server.createContext("/api/runs", new RunsApiHandler());
            server.createContext("/", new StaticHandler());
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.println("[Coordinator] Servidor HTTP iniciado em /, /webhook e /api/runs.");
        } catch (IOException e) {
            System.out.println("[Coordinator] Erro ao iniciar servidor HTTP: " + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    class WebhookHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {

            System.out.println("[Webhook] Requisição recebida.");

            String body = new String(ex.getRequestBody().readAllBytes());
            System.out.println("[Webhook] Body recebido: " + body);

            JSONObject json = new JSONObject(body);

            String requestedRepo = json.getString("repository");
            String gitRef = extractGitRef(json, requestedRepo);
            String repo = normalizeRepositoryUrl(requestedRepo);
            String run_id = UUID.randomUUID().toString();
            String repoName = extract(repo);
            String safeVersion = safeVersion(gitRef);
            String repoPath = "/repos/" + repoName + ":" + safeVersion;
            String sonarProjectKey = buildSonarProjectKey(repo, safeVersion);
            String sonarProjectName = repoPath;

            System.out.println("[Webhook] Repository requested: " + requestedRepo);
            System.out.println("[Webhook] Repository clone URL: " + repo);
            System.out.println("[Webhook] Git ref: " + gitRef);
            System.out.println("[Webhook] Safe version: " + safeVersion);
            System.out.println("[Webhook] Run ID criado: " + run_id);
            System.out.println("[Webhook] Repo name: " + repoName);
            System.out.println("[Webhook] Repo path: " + repoPath);
            System.out.println("[Webhook] Sonar project key: " + sonarProjectKey);
            System.out.println("[Webhook] Sonar project name: " + sonarProjectName);

            RunState s = new RunState();
            s.repoUrl = repo;
            s.repoPath = repoPath;

            runs.put(run_id, s);

            rt(run_id, "RunState criado em memória.");

            create(run_id, repo, repoPath, gitRef, sonarProjectKey, sonarProjectName);

            rt(run_id, "Enviando comando RUN_GIT para GitAgent.");

            sendTo("git", "RUN_GIT", Map.of(
                    "run_id", run_id,
                    "repo_url", repo,
                    "repo_path", repoPath,
                    "git_ref", gitRef,
                    "safe_version", safeVersion,
                    "sonar_project_key", sonarProjectKey,
                    "sonar_project_name", sonarProjectName));

            respond(ex, run_id);

            rt(run_id, "Resposta HTTP enviada ao cliente.");
        }
    }

    class RunsApiHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 204, "");
                return;
            }

            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendJson(ex, 405, new Document("error", "method_not_allowed").toJson());
                return;
            }

            try {
                String path = ex.getRequestURI().getPath();
                String prefix = "/api/runs";
                String rest = path.length() > prefix.length() ? path.substring(prefix.length()) : "";

                if (rest.isBlank() || "/".equals(rest)) {
                    sendJson(ex, 200, listRunsJson());
                    return;
                }

                String route = rest.startsWith("/") ? rest.substring(1) : rest;
                String[] parts = route.split("/");
                String runId = parts.length > 0 ? parts[0] : "";
                String action = parts.length > 1 ? parts[1] : "";

                if ("status".equals(action)) {
                    sendJson(ex, 200, loadRunStatusJson(runId));
                    return;
                }

                if ("events".equals(action)) {
                    streamRunEvents(ex, runId);
                    return;
                }

                sendJson(ex, 200, loadRunJson(runId));
            } catch (Exception e) {
                sendJson(ex, 500, new Document("error", e.getMessage()).toJson());
            }
        }
    }

    class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                sendPlain(ex, 204, "", "text/plain; charset=utf-8");
                return;
            }

            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                sendPlain(ex, 405, "Method not allowed", "text/plain; charset=utf-8");
                return;
            }

            String path = ex.getRequestURI().getPath();
            String resource = switch (path) {
                case "/", "/index.html" -> "public/index.html";
                case "/app.css" -> "public/app.css";
                case "/app.js" -> "public/app.js";
                default -> null;
            };

            if (resource == null) {
                sendPlain(ex, 404, "Not found", "text/plain; charset=utf-8");
                return;
            }

            try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
                if (in == null) {
                    sendPlain(ex, 404, "Not found", "text/plain; charset=utf-8");
                    return;
                }

                String contentType = resource.endsWith(".css")
                        ? "text/css; charset=utf-8"
                        : resource.endsWith(".js")
                        ? "application/javascript; charset=utf-8"
                        : "text/html; charset=utf-8";

                byte[] out = in.readAllBytes();
                addCors(ex);
                ex.getResponseHeaders().set("Content-Type", contentType);
                ex.sendResponseHeaders(200, out.length);
                ex.getResponseBody().write(out);
                ex.close();
            }
        }
    }

    private void sendTo(String type, String ontology, Map payload) {

        System.out.println("[Coordinator][JADE-SEND] Preparando envio.");
        System.out.println("[Coordinator][JADE-SEND] Destination type: " + type);
        System.out.println("[Coordinator][JADE-SEND] Ontology: " + ontology);
        System.out.println("[Coordinator][JADE-SEND] Payload: " + payload);

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(ontology);
        msg.setContent(gson.toJson(payload));

        List<AID> agents = AgentDirectory.find(this, type);

        if (agents != null && !agents.isEmpty()) {
            System.out.println("[Coordinator][JADE-SEND] Agentes encontrados no AgentDirectory: " + agents.size());

            for (AID agent : agents) {
                System.out.println("[Coordinator][JADE-SEND] Receiver: " + agent.getLocalName());
                msg.addReceiver(agent);
            }
        } else {
            AID fallback = new AID(type + "_agent", AID.ISLOCALNAME);
            System.out.println("[Coordinator][JADE-SEND] Nenhum agente encontrado no AgentDirectory.");
            System.out.println("[Coordinator][JADE-SEND] Usando fallback receiver: " + fallback.getLocalName());
            msg.addReceiver(fallback);
        }

        send(msg);

        System.out.println("[Coordinator][JADE-SEND] Mensagem enviada com sucesso.");
    }

    private void fail(String run_id, String type, Map payload) {

        RunState s = runs.get(run_id);
        Object reason = payload == null ? type : payload.getOrDefault("reason", type);

        rt(run_id, "Pipeline marcado como FAILED.");
        rt(run_id, "Tipo da falha: " + type);
        rt(run_id, "Motivo: " + reason);

        if (s != null) {
            s.failed = true;
            s.stage = "FAILED";
            s.pct = 100;

            rt(run_id, "RunState em memória atualizado para FAILED.");
        } else {
            rt(run_id, "RunState não encontrado em memória durante falha.");
        }

        update(run_id,
                "failed", true,
                "reason", reason,
                "stage", "FAILED",
                "progress_pct", 100,
                "failed_at", Instant.now().toString());

        log(run_id, "coordinator", "FAILED", type, Map.of("reason", reason));

        rt(run_id, "Falha persistida no MongoDB.");
    }

    private void create(String run_id, String url, String path, String gitRef, String sonarProjectKey, String sonarProjectName) {

        rt(run_id, "Criando registro inicial no MongoDB.");

        MongoCollection<Document> col = MongoHelper.getDatabase().getCollection("runStatus");

        col.updateOne(new Document("run_id", run_id),
                new Document("$setOnInsert", new Document("run_id", run_id)
                        .append("repo_url", url)
                        .append("repo_path", path)
                        .append("git_ref", gitRef)
                        .append("safe_version", safeVersion(gitRef))
                        .append("sonar_project_key", sonarProjectKey)
                        .append("sonar_project_name", sonarProjectName)
                        .append("created_at", Instant.now().toString())
                        .append("stage", "START")
                        .append("progress_pct", 0)
                        .append("failed", false)),
                new UpdateOptions().upsert(true));

        rt(run_id, "Registro inicial criado/garantido no MongoDB.");

        log(run_id, "coordinator", "RUN_CREATED", "RUN_GIT", Map.of(
                "repo_url", url,
                "repo_path", path,
                "git_ref", gitRef,
                "safe_version", safeVersion(gitRef),
                "sonar_project_key", sonarProjectKey,
                "sonar_project_name", sonarProjectName));
    }

    private void update(String run_id, Object... kv) {

        MongoCollection<Document> col = MongoHelper.getDatabase().getCollection("runStatus");

        Document set = new Document();

        for (int i = 0; i < kv.length; i += 2) {
            set.append(String.valueOf(kv[i]), kv[i + 1]);
        }

        System.out.println("[Coordinator][MongoUpdate][run=" + run_id + "] " + set.toJson());

        col.updateOne(new Document("run_id", run_id),
                new Document("$set", set),
                new UpdateOptions().upsert(true));
    }

    private boolean isFailed(String run_id, RunState s) {
        if (s != null && s.failed) {
            rt(run_id, "Mensagem ignorada: RunState já está FAILED.");
            log(run_id, "coordinator", "IGNORED_AFTER_FAILED", s.stage, Map.of());
            return true;
        }

        Document status = MongoHelper.getDatabase()
                .getCollection("runStatus")
                .find(new Document("run_id", run_id))
                .first();

        boolean failed = status != null &&
                (Boolean.TRUE.equals(status.getBoolean("failed")) || "FAILED".equals(status.getString("stage")));

        if (failed && s != null) {
            s.failed = true;
            s.stage = "FAILED";
            s.pct = 100;

            rt(run_id, "Mensagem ignorada: MongoDB indica stage FAILED.");
            log(run_id, "coordinator", "IGNORED_AFTER_FAILED", "FAILED_IN_MONGO", Map.of());
        }

        return failed;
    }

    private void log(String run_id, String agent, String event, String ontology, Object data) {
        try {
            MongoHelper.getDatabase().getCollection("logs").insertOne(new Document()
                    .append("run_id", run_id)
                    .append("agent", agent)
                    .append("event", event)
                    .append("ontology", ontology)
                    .append("data", data == null ? new Document() : Document.parse(gson.toJson(data)))
                    .append("created_at", Instant.now().toString()));

            System.out.println("[Coordinator][MongoLog][run=" + run_id + "] "
                    + "agent=" + agent
                    + ", event=" + event
                    + ", ontology=" + ontology);

        } catch (Exception e) {
            System.out.println("[Coordinator][MongoLog][run=" + run_id + "] Falha ao registrar log: " + e.getMessage());
        }
    }

    private void respond(HttpExchange ex, String run_id) throws IOException {

        JSONObject r = new JSONObject();
        r.put("run_id", run_id);

        byte[] out = r.toString().getBytes();

        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();

        rt(run_id, "HTTP 200 enviado com body: " + r);
    }

    private String listRunsJson() {
        MongoDatabase db = MongoHelper.getDatabase();
        List<String> items = new ArrayList<>();

        for (Document status : db.getCollection("runStatus")
                .find()
                .sort(new Document("created_at", -1))
                .limit(100)) {
            String runId = status.getString("run_id");
            Document item = new Document("status", status)
                    .append("code_metrics", findOne(db, "code_metrics", runId))
                    .append("project", findOne(db, "projectsReport", runId))
                    .append("llm_report", findOne(db, "llm_reports", runId));
            items.add(item.toJson());
        }

        return "[" + String.join(",", items) + "]";
    }

    private String loadRunJson(String runId) {
        MongoDatabase db = MongoHelper.getDatabase();
        Document doc = new Document("run_id", runId)
                .append("status", findOne(db, "runStatus", runId))
                .append("code_metrics", findOne(db, "code_metrics", runId))
                .append("sonar_metrics", findOne(db, "sonar_metrics", runId))
                .append("project", findOne(db, "projectsReport", runId))
                .append("code_report", findOne(db, "codeReport", runId))
                .append("llm_report", findOne(db, "llm_reports", runId))
                .append("logs", findMany(db, "logs", runId, 120));
        return doc.toJson();
    }

    private String loadRunStatusJson(String runId) {
        MongoDatabase db = MongoHelper.getDatabase();
        Document status = findOne(db, "runStatus", runId);
        Document report = findOne(db, "llm_reports", runId);

        Document doc = new Document("run_id", runId)
                .append("status", status)
                .append("terminal", isTerminal(status))
                .append("llm_report_available", report != null)
                .append("server_time", Instant.now().toString());

        return doc.toJson();
    }

    private void streamRunEvents(HttpExchange ex, String runId) throws IOException {
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        int maxSeconds = Integer.parseInt(System.getenv().getOrDefault("RUN_EVENTS_MAX_SECONDS", "3600"));
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        String lastPayload = "";

        try {
            while (System.currentTimeMillis() < deadline) {
                String payload = loadRunJson(runId);
                if (!payload.equals(lastPayload)) {
                    writeSse(ex, "run", payload);
                    lastPayload = payload;
                } else {
                    writeSse(ex, "heartbeat", new Document("server_time", Instant.now().toString()).toJson());
                }

                Document status = MongoHelper.getDatabase()
                        .getCollection("runStatus")
                        .find(new Document("run_id", runId))
                        .first();

                if (isTerminal(status)) {
                    writeSse(ex, "done", loadRunStatusJson(runId));
                    break;
                }

                Thread.sleep(1500);
            }
        } catch (IOException ignored) {
            // Client disconnected.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ex.close();
        }
    }

    private void writeSse(HttpExchange ex, String event, String data) throws IOException {
        String payload = "event: " + event + "\n"
                + "data: " + data.replace("\n", "\\n") + "\n\n";
        ex.getResponseBody().write(payload.getBytes(StandardCharsets.UTF_8));
        ex.getResponseBody().flush();
    }

    private boolean isTerminal(Document status) {
        if (status == null) {
            return false;
        }

        String stage = status.getString("stage");
        return "DONE".equals(stage)
                || "FAILED".equals(stage)
                || Boolean.TRUE.equals(status.getBoolean("failed"));
    }

    private Document findOne(MongoDatabase db, String collection, String runId) {
        if (runId == null) {
            return null;
        }
        return db.getCollection(collection).find(new Document("run_id", runId)).first();
    }

    private List<Document> findMany(MongoDatabase db, String collection, String runId, int limit) {
        List<Document> out = new ArrayList<>();
        if (runId == null) {
            return out;
        }

        for (Document doc : db.getCollection(collection)
                .find(new Document("run_id", runId))
                .sort(new Document("created_at", 1))
                .limit(limit)) {
            out.add(doc);
        }
        return out;
    }

    private void sendJson(HttpExchange ex, int status, String json) throws IOException {
        sendPlain(ex, status, json == null ? "" : json, "application/json; charset=utf-8");
    }

    private void sendPlain(HttpExchange ex, int status, String text, String contentType) throws IOException {
        byte[] out = text == null ? new byte[0] : text.getBytes(StandardCharsets.UTF_8);
        addCors(ex);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(status, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private String extract(String url) {
        String n = url.substring(url.lastIndexOf("/") + 1);
        String repoName = n.replace(".git", "");

        System.out.println("[Coordinator] Nome do repositório extraído: " + repoName);

        return repoName;
    }

    private String extractGitRef(JSONObject json, String repoUrl) {
        for (String key : List.of("git_ref", "branch", "version", "ref")) {
            if (json.has(key) && !json.isNull(key)) {
                String value = json.optString(key, "").trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }

        String marker = "/tree/";
        int idx = repoUrl == null ? -1 : repoUrl.indexOf(marker);
        if (idx >= 0) {
            String value = repoUrl.substring(idx + marker.length()).trim();
            return value.isBlank() ? "" : value;
        }

        return "";
    }

    private String normalizeRepositoryUrl(String repoUrl) {
        String normalized = repoUrl == null ? "" : repoUrl.trim();
        int treeIdx = normalized.indexOf("/tree/");
        if (treeIdx >= 0) {
            normalized = normalized.substring(0, treeIdx);
        }

        if (normalized.startsWith("https://github.com/") && !normalized.endsWith(".git")) {
            normalized = normalized + ".git";
        }

        return normalized;
    }

    private String buildSonarProjectKey(String repoUrl, String safeVersion) {
        String normalized = repoUrl == null ? "repository" : repoUrl.trim();

        if (normalized.endsWith(".git")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }

        normalized = normalized.replace('\\', '/');
        String[] parts = normalized.split("/");
        String repo = parts.length > 0 ? parts[parts.length - 1] : "repository";
        String owner = parts.length > 1 ? parts[parts.length - 2] : "local";

        String key = "sma:" + sanitizeSonarKeyPart(owner) + ":" + sanitizeSonarKeyPart(repo) + ":" + safeVersion;

        if (key.matches("[0-9:._-]+")) {
            key = "sma:repo:" + key;
        }

        System.out.println("[Coordinator] Sonar project key gerada: " + key);

        return key;
    }

    private String sanitizeSonarKeyPart(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9._:-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        sanitized = sanitized.replaceAll("^[.:-]+|[.:-]+$", "");

        String result = sanitized.isBlank() ? "repository" : sanitized;

        System.out.println("[Coordinator] Sanitized Sonar key part: " + value + " -> " + result);

        return result;
    }

    private String safeVersion(String gitRef) {
        String ref = gitRef == null || gitRef.isBlank() ? "default" : gitRef;
        String sanitized = ref.replaceAll("[^A-Za-z0-9._:-]", "-");
        sanitized = sanitized.replaceAll("-+", "-");
        sanitized = sanitized.replaceAll("^[.:-]+|[.:-]+$", "");
        return sanitized.isBlank() ? "default" : sanitized;
    }

    private void rt(String runId, String msg) {
        System.out.printf(
                "[%s][Coordinator][run=%s] %s%n",
                Instant.now(),
                runId,
                msg
        );
    }
}
