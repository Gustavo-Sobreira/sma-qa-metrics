package br.uerj.multiagentes.agentes;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;

import org.json.JSONObject;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import jade.core.AID;
import jade.core.Agent;

public class CoordinatorAgent extends Agent {

    private static final String REPOS_DIR = "/app/repos";
    private static final String REPO_PATH = "/app/repos/repo";

    @Override
    protected void setup() {
        System.out.println("[CoordinatorAgent] REPOS_DIR = " + REPOS_DIR);
        startHttpServer(8090);
        System.out.println("[CoordinatorAgent] Ready. Webhook at 0.0.0.0:8090/webhook");
    }

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
            deleteDirectory(new File(REPO_PATH));
            new File(REPO_PATH).mkdirs();
            String log = runGitClone(repoUrl, REPO_PATH);

            JSONObject response = new JSONObject();
            response.put("log", log);

            if (log.contains("fatal") || log.contains("error")) {
                response.put("ok", false);
                response.put("msg", "git clone failed");
            } else {
                response.put("ok", true);
                response.put("msg", "repo cloned");

                sendToQaAgent(REPO_PATH);
            }

            byte[] respBytes = response.toString().getBytes();
            exchange.sendResponseHeaders(200, respBytes.length);
            exchange.getResponseBody().write(respBytes);
            exchange.close();
        }
    }

    private void sendToQaAgent(String repoPath) {
        try {
            JSONObject json = new JSONObject();
            json.put("repoPath", repoPath);

            jade.lang.acl.ACLMessage msg = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);
            msg.addReceiver(new AID("qa_agent", AID.ISLOCALNAME));
            msg.setOntology("Repo-Cloned");
            msg.setContent(json.toString());
            send(msg);

            System.out.println("[CoordinatorAgent] Enviado ao QaAgent: " + repoPath);

        } catch (Exception e) {
            System.err.println("[CoordinatorAgent] Erro ao enviar para QaAgent: " + e.getMessage());
        }
    }

    private String runGitClone(String url, String path) {
        StringBuilder output = new StringBuilder();

        try {
            ProcessBuilder pb = new ProcessBuilder("git", "clone", url, path);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
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
                if (f.isDirectory()) {
                    deleteDirectory(f);
                } else {
                    f.delete();
                }
            }
        }
        dir.delete();
    }
}
