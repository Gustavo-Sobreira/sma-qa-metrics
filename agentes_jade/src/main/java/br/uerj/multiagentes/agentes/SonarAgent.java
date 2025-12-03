package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import br.uerj.multiagentes.utils.MongoHelper;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.bson.Document;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

public class SonarAgent extends Agent {
    private Gson gson = new Gson();
    private HttpClient http = HttpClient.newHttpClient();
    private String workerUrl = System.getenv().getOrDefault("SONAR_WORKER_URL", "http://sonar-worker:9100/scan");

    @Override
    protected void setup() {
        System.out.println("[SonarAgent] Ready. Worker: " + workerUrl);
        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null) {
                    try {
                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = (String) payload.get("run_id");
                        String repoPath = (String) payload.get("repo_path");
                        System.out.println("[SonarAgent] Received scan request for " + runId);

                        // call sonar-worker
                        String body = gson.toJson(Map.of("run_id", runId, "repo_path", repoPath));
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create(workerUrl))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

                        // update mongo
                        MongoDatabase db = MongoHelper.getDatabase();
                        MongoCollection<Document> runs = db.getCollection("run_status");
                        runs.updateOne(new Document("run_id", runId),
                                new Document("$set", new Document("sonar_done", true).append("sonar_finished_at",
                                        Instant.now().toString())));

                        // notify llm
                        ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                        notify.addReceiver(new jade.core.AID("llm", jade.core.AID.ISLOCALNAME));
                        notify.setContent(gson.toJson(Map.of("type", "SONAR_DONE", "run_id", runId)));
                        send(notify);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    block();
                }
            }
        });
    }
}