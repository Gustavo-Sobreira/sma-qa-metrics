package br.uerj.multiagentes.agentes;

import jade.core.Agent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import br.uerj.multiagentes.utils.MongoHelper;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.UUID;

import org.bson.Document;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import java.net.InetSocketAddress;

public class CoordinatorAgent extends Agent {
    private Gson gson = new Gson();
    private String reposDir;

    @Override
    protected void setup() {
        // 🔧 CORRIGIDO: diretório default agora é seguro
        reposDir = System.getenv().getOrDefault(
                "REPOS_DIR",
                System.getProperty("user.dir") + "/repos"
        );

        try {
            Files.createDirectories(Paths.get(reposDir));
            System.out.println("[CoordinatorAgent] REPOS_DIR = " + reposDir);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // HTTP server
        startHttpServer();
        System.out.println("[CoordinatorAgent] Ready. Webhook at 0.0.0.0:8080/webhook");
    }

    private void startHttpServer() {
        try {
            // 🔧 CORRIGIDO: porta 8080 para evitar conflito
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/webhook", new WebhookHandler());
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    class WebhookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                // read webhook body
                String body = new String(exchange.getRequestBody().readAllBytes());
                JsonObject payload = gson.fromJson(body, JsonObject.class);

                // repo URL
                String repo = payload.has("repository") ?
                        payload.get("repository").getAsString() : null;

                if (repo == null) {
                    byte[] error = "{\"error\":\"repository missing\"}".getBytes();
                    exchange.sendResponseHeaders(400, error.length);
                    exchange.getResponseBody().write(error);
                    exchange.getResponseBody().close();
                    return;
                }

                // generate run ID
                String runId = Instant.now().toString().replaceAll("[:.]", "_") + "_"
                        + UUID.randomUUID().toString().substring(0, 6);

                String dest = Paths.get(reposDir, runId).toString();

                // clone repo
                ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1", repo, dest);
                pb.redirectErrorStream(true);

                Process p = pb.start();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    out.append(line).append("\n");
                }

                int exit = p.waitFor();

                if (exit != 0) {
                    String resp = gson.toJson(
                            java.util.Map.of(
                                    "ok", false,
                                    "msg", "git clone failed",
                                    "log", out.toString()
                            )
                    );
                    exchange.sendResponseHeaders(500, resp.getBytes().length);
                    exchange.getResponseBody().write(resp.getBytes());
                    exchange.getResponseBody().close();
                    return;
                }

                // save to Mongo
                MongoDatabase db = MongoHelper.getDatabase();
                MongoCollection<Document> runs = db.getCollection("run_status");

                Document doc = new Document("run_id", runId)
                        .append("repo", repo)
                        .append("repo_path", dest)
                        .append("cloned_at", Instant.now().toString())
                        .append("qa_done", false)
                        .append("metrics_done", false)
                        .append("sonar_done", false)
                        .append("php_done", false);

                runs.insertOne(doc);

                // notify QA agent
                ACLMessage msgQA = new ACLMessage(ACLMessage.INFORM);
                msgQA.addReceiver(new AID("qa", AID.ISLOCALNAME));
                msgQA.setContent(gson.toJson(
                        java.util.Map.of("type", "NEW_REPO", "run_id", runId, "repo_path", dest)
                ));
                send(msgQA);

                // notify Metrics agent
                ACLMessage msgMetrics = new ACLMessage(ACLMessage.INFORM);
                msgMetrics.addReceiver(new AID("metrics", AID.ISLOCALNAME));
                msgMetrics.setContent(gson.toJson(
                        java.util.Map.of("type", "NEW_REPO", "run_id", runId, "repo_path", dest)
                ));
                send(msgMetrics);

                // response to webhook
                String resp = gson.toJson(java.util.Map.of("ok", true, "run_id", runId));
                exchange.sendResponseHeaders(200, resp.getBytes().length);
                exchange.getResponseBody().write(resp.getBytes());
                exchange.getResponseBody().close();

                System.out.println("[CoordinatorAgent] Repo cloned: " + dest + " runId=" + runId);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
