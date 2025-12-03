package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import br.uerj.multiagentes.utils.MongoHelper;
import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import org.bson.Document;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Instant;
import java.util.Map;

public class QaAgent extends Agent {
    private Gson gson = new Gson();
    private HttpClient http = HttpClient.newHttpClient();

    @Override
    protected void setup() {
        System.out.println("[QaAgent] Ready.");
        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null) {
                    try {
                        String content = msg.getContent();
                        Map payload = gson.fromJson(content, Map.class);
                        if (payload != null && "NEW_REPO".equals(payload.get("type"))) {
                            String runId = (String) payload.get("run_id");
                            String repoPath = (String) payload.get("repo_path");
                            System.out.println("[QaAgent] New repo: " + repoPath + " run=" + runId);

                            // send request to SonarAgent and PhpAgent via ACL
                            ACLMessage sonarReq = new ACLMessage(ACLMessage.REQUEST);
                            sonarReq.addReceiver(new AID("sonar", AID.ISLOCALNAME));
                            sonarReq.setContent(gson.toJson(Map.of("run_id", runId, "repo_path", repoPath)));
                            send(sonarReq);

                            ACLMessage phpReq = new ACLMessage(ACLMessage.REQUEST);
                            phpReq.addReceiver(new AID("php", AID.ISLOCALNAME));
                            phpReq.setContent(gson.toJson(Map.of("run_id", runId, "repo_path", repoPath)));
                            send(phpReq);

                            // update run_status -> qa_started
                            MongoDatabase db = MongoHelper.getDatabase();
                            MongoCollection<Document> runs = db.getCollection("run_status");
                            runs.updateOne(new Document("run_id", runId),
                                    new Document("$set", new Document("qa_started_at", Instant.now().toString())));

                            // notify llm that new data is being generated (status pending)
                            ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                            notify.addReceiver(new AID("llm", AID.ISLOCALNAME));
                            notify.setContent(gson.toJson(Map.of("type", "QA_STARTED", "run_id", runId)));
                            send(notify);
                        }
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