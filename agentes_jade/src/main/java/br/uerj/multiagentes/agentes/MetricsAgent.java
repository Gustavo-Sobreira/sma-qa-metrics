package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.core.AID;

import org.bson.Document;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoCollection;

import br.uerj.multiagentes.utils.MongoHelper;

public class MetricsAgent extends Agent {
    private Gson gson = new Gson();

    @Override
    protected void setup() {
        System.out.println("[MetricsAgent] Ready.");
        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);
                if (msg != null) {
                    try {
                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        if (payload != null && "NEW_REPO".equals(payload.get("type"))) {
                            String runId = (String) payload.get("run_id");
                            String repoPath = (String) payload.get("repo_path");
                            System.out.println("[MetricsAgent] Processing git log for " + runId);

                            // --- Git analysis ---
                            ProcessBuilder pb = new ProcessBuilder("git", "-C", repoPath, "log", "--pretty=%an");
                            pb.redirectErrorStream(true);
                            Process p = pb.start();
                            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                            Map<String, Integer> commitsByAuthor = new HashMap<>();
                            String line;
                            int total = 0;
                            while ((line = br.readLine()) != null) {
                                commitsByAuthor.put(line, commitsByAuthor.getOrDefault(line, 0) + 1);
                                total++;
                            }
                            p.waitFor();

                            Document metrics = new Document("run_id", runId)
                                    .append("repo_path", repoPath)
                                    .append("total_commits", total)
                                    .append("commits_by_author", commitsByAuthor)
                                    .append("generated_at", Instant.now().toString());

                            // --- MongoDB ---
                            MongoDatabase db = MongoHelper.getDatabase();
                            MongoCollection<Document> col = db.getCollection("metrics");
                            col.insertOne(metrics);

                            MongoCollection<Document> runs = db.getCollection("run_status");
                            runs.updateOne(
                                    new Document("run_id", runId),
                                    new Document("$set",
                                            new Document("metrics_done", true)
                                                    .append("metrics_finished_at", Instant.now().toString()))
                            );

                            // --- Notify LLM ---
                            ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                            notify.addReceiver(new AID("llm", AID.ISLOCALNAME));
                            notify.setContent(gson.toJson(Map.of("type", "METRICS_DONE", "run_id", runId)));
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
