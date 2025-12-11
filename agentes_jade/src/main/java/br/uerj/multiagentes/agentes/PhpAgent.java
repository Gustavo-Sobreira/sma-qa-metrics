package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

import com.mongodb.client.*;
import org.bson.Document;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

public class PhpAgent extends Agent {

    private MongoClient mongo;
    private MongoCollection<Document> collection;

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newHttpClient();

    private static final String WORKER_URL = System.getenv().getOrDefault(
            "PHPMETRICS_WORKER_URL",
            "http://phpmetrics-worker:9200/scan");

    private static final String MONGO_URI = System.getenv().getOrDefault(
            "MONGO_URI",
            "mongodb://mongo:27017");

    @Override
    protected void setup() {
        System.out.println("[PhpAgent] Pronto. Worker: " + WORKER_URL);

        try {
            mongo = MongoClients.create(MONGO_URI);
            MongoDatabase db = mongo.getDatabase("agents_db");
            collection = db.getCollection("phpmetrics_results");
            System.out.println("[PhpAgent] Connected to MongoDB.");
        } catch (Exception e) {
            System.err.println("[PhpAgent] ERROR connecting to MongoDB!");
            e.printStackTrace();
        }

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {

                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }

                String runId = null;
                String repoPath = null;

                try {

                    if (msg.getPerformative() == ACLMessage.REQUEST &&
                            "RUN_PHPMETRICS".equals(msg.getOntology())) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        runId = (String) payload.get("run_id");
                        repoPath = (String) payload.get("repo_path");

                        System.out.println("[PhpAgent] Running phpmetrics for run=" + runId);

                        Map<String, Object> body = new HashMap<>();
                        body.put("run_id", runId);
                        body.put("repo_path", repoPath);

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(WORKER_URL))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                                .build();

                        HttpResponse<String> resp = http.send(request,
                                HttpResponse.BodyHandlers.ofString());

                        Map<String, Object> workerResp = gson.fromJson(resp.body(), Map.class);

                        String stdout = (String) workerResp.get("stdout");

                        Map<String, Object> parsedMetrics = parsePhpmetricsOutput(stdout);

                        Document doc = new Document()
                                .append("run_id", runId)
                                .append("repo_path", repoPath)
                                .append("agent", "phpmetrics")
                                .append("timestamp", System.currentTimeMillis())
                                .append("ok", workerResp.get("ok"))
                                .append("exit_code", workerResp.get("exit_code"))
                                .append("stdout", stdout)
                                .append("stderr", workerResp.get("stderr"));

                        for (String section : parsedMetrics.keySet()) {
                            String key = section.toLowerCase().replace(" ", "_");
                            doc.append(key, Document.parse(
                                    gson.toJson(parsedMetrics.get(section))));
                        }

                        if (workerResp.get("report_json") != null) {
                            doc.append("report_json",
                                    Document.parse(
                                            gson.toJson(workerResp.get("report_json"))));
                        }

                        collection.insertOne(doc);
                        System.out.println("[PhpAgent] Metrics saved.");

                        Map<String, Object> done = new HashMap<>();
                        done.put("run_id", runId);
                        done.put("agent", "php");

                        ACLMessage doneMsg = new ACLMessage(ACLMessage.INFORM);
                        doneMsg.addReceiver(new AID("qa_agent", AID.ISLOCALNAME));
                        doneMsg.setOntology("QA_SUBTASK_DONE");
                        doneMsg.setContent(gson.toJson(done));
                        send(doneMsg);
                    }

                } catch (Exception e) {

                    System.err.println("[PhpAgent] ERROR: " + e.getMessage());

                    Map<String, Object> done = new HashMap<>();
                    done.put("run_id", runId);
                    done.put("agent", "php");
                    done.put("error", e.getMessage());

                    ACLMessage doneMsg = new ACLMessage(ACLMessage.INFORM);
                    doneMsg.addReceiver(new AID("qa_agent", AID.ISLOCALNAME));
                    doneMsg.setOntology("QA_SUBTASK_DONE");
                    doneMsg.setContent(gson.toJson(done));
                    send(doneMsg);
                }
            }
        });
    }

    private Map<String, Object> parsePhpmetricsOutput(String stdout) {
        Map<String, Object> result = new HashMap<>();

        stdout = stdout.replaceAll("\u001B\\[[;\\d]*m", "")
                .replace("[2K", "");

        String[] sections = {
                "LOC",
                "Object oriented programming",
                "Coupling",
                "Package",
                "Complexity",
                "Bugs",
                "Violations"
        };

        String current = null;
        Map<String, Object> currentBlock = new HashMap<>();
        String[] lines = stdout.split("\n");

        for (String raw : lines) {
            String line = raw.strip();

            if (line.isEmpty())
                continue;

            boolean isSection = false;
            for (String s : sections) {
                if (line.equalsIgnoreCase(s)) {
                    if (current != null && !currentBlock.isEmpty()) {
                        result.put(current, new HashMap<>(currentBlock));
                        currentBlock.clear();
                    }
                    current = s;
                    isSection = true;
                    break;
                }
            }

            if (isSection || current == null)
                continue;

            String[] parts = line.split("\\s{2,}");
            if (parts.length == 2) {
                String key = parts[0].trim()
                        .replace(" ", "_")
                        .replace("-", "_")
                        .toLowerCase();
                try {
                    currentBlock.put(key,
                            Integer.parseInt(parts[1].trim()));
                } catch (Exception e) {
                    currentBlock.put(key, parts[1].trim());
                }
            }
        }

        if (current != null && !currentBlock.isEmpty()) {
            result.put(current, currentBlock);
        }

        return result;
    }
}
