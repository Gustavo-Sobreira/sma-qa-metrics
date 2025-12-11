package br.uerj.multiagentes.agentes;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.json.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import jade.core.AID;
import jade.core.Agent;

public class CoordinatorAgent extends Agent {

    private static final String REPOS_DIR = System.getenv().getOrDefault("REPOS_DIR", "/repos");
    private static final String REPO_PATH = REPOS_DIR + "/repo";

    @Override
    protected void setup() {
        startHttpServer(8090);
        System.out.println("[ 🧠 - CoordinatorAgent ]\n     |-> REPOS_DIR = " + REPOS_DIR
                + "\n     |-> Escutando na 8090/webhook\n     |-> Pronto");
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

            String log;

            File repoDir = new File(REPO_PATH);
            if (repoDir.exists() && new File(REPO_PATH + "/.git").exists()) {
                log = runGitPull(REPO_PATH);
                System.out.println(
                        "[ 🧠 - CoordinatorAgent ]\n     |-> Projeto existente.\n     |-> git pull concluído.");
            } else {
                deleteDirectory(repoDir);
                repoDir.mkdirs();
                log = runGitClone(repoUrl, REPO_PATH);
                System.out.println(
                        "[ 🧠 - CoordinatorAgent ]\n     |->  Novo projeto detectado.\n     |-> git clone concluído.");
            }

            JSONObject response = new JSONObject();
            response.put("log", log);

            if (log.contains("fatal") || log.contains("error")) {
                response.put("ok", false);
                response.put("msg", "git pull/clone failed");
            } else {
                response.put("ok", true);
                response.put("msg", "repo updated");

                String runId = UUID.randomUUID().toString();
                sendToMetricsAgents(runId, REPO_PATH);
            }

            byte[] respBytes = response.toString().getBytes();
            exchange.sendResponseHeaders(200, respBytes.length);
            exchange.getResponseBody().write(respBytes);
            exchange.close();
        }
    }

    private void sendToMetricsAgents(String runId, String repoPath) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("run_id", runId);
            payload.put("repo_path", repoPath);

            jade.lang.acl.ACLMessage msg = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);

            msg.addReceiver(new AID("qa_agent", AID.ISLOCALNAME));
            msg.setOntology("Repo-Cloned");
            msg.setContent(new JSONObject(payload).toString());
            send(msg);

            System.out.println("[ 🧠 - CoordinatorAgent ]\n     |->  Projeto enviado ao CodeAnalyzerAgent.");

        } catch (Exception e) {
            System.err.println("[ 🧠 - CoordinatorAgent ]\n     |->  Erro ao enviar projeto ao CodeAnalyzerAgent. \n "
                    + e.getMessage());
            e.printStackTrace();
        }

        try {
            Map<String, Object> gitPayload = new HashMap<>();
            gitPayload.put("type", "NEW_REPO");
            gitPayload.put("run_id", runId);
            gitPayload.put("repo_path", repoPath);

            jade.lang.acl.ACLMessage gitMsg = new jade.lang.acl.ACLMessage(jade.lang.acl.ACLMessage.INFORM);

            gitMsg.addReceiver(new AID("git_log_agent", AID.ISLOCALNAME));
            gitMsg.setContent(new JSONObject(gitPayload).toString());

            send(gitMsg);

            System.out.println("[ 🧠 - CoordinatorAgent ]\n     |->  Projeto enviado ao GitLogAgent.");

        } catch (Exception e) {
            System.err.println(
                    "[ 🧠 - CoordinatorAgent ]\n     |->  Erro ao enviar projeto ao GitLogAgent\n " + e.getMessage());
            e.printStackTrace();
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

    private String runGitPull(String path) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", path, "pull");
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
        if (!dir.exists())
            return;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory())
                    deleteDirectory(f);
                else
                    f.delete();
            }
        }

        dir.delete();
    }
}
