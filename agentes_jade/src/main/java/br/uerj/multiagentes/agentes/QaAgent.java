package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import br.uerj.multiagentes.utils.MongoHelper;

import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

import org.bson.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class QaAgent extends Agent {

    private final Gson gson = new Gson();
    private final Map<String, Set<String>> pending = new HashMap<>();

    @Override
    protected void setup() {
        System.out.println("[QaAgent] Ready.");

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {

                ACLMessage msg = myAgent.receive();
                if (msg == null) { block(); return; }

                try {

                    String ontology = msg.getOntology();

                    if (msg.getPerformative() == ACLMessage.INFORM &&
                        "Repo-Cloned".equals(ontology)) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = (String) payload.get("run_id");
                        String repoPath = (String) payload.get("repo_path");

                        pending.put(runId, new HashSet<>());

                        Map<String, Object> sonarBody = new HashMap<>();
                        sonarBody.put("run_id", runId);
                        sonarBody.put("repo_path", repoPath);

                        ACLMessage sonarReq = new ACLMessage(ACLMessage.REQUEST);
                        sonarReq.addReceiver(new AID("sonar_agent", AID.ISLOCALNAME));
                        sonarReq.setOntology("RUN_SONAR");
                        sonarReq.setContent(gson.toJson(sonarBody));
                        send(sonarReq);

    // Sá bosta parou de funcionar na versão 8 e o animal aqui não testou
                        // Map<String, Object> phpBody = new HashMap<>();
                        // phpBody.put("run_id", runId);
                        // phpBody.put("repo_path", repoPath);

                        // ACLMessage phpReq = new ACLMessage(ACLMessage.REQUEST);
                        // phpReq.addReceiver(new AID("php_agent", AID.ISLOCALNAME));
                        // phpReq.setOntology("RUN_PHPMETRICS");
                        // phpReq.setContent(gson.toJson(phpBody));
                        // send(phpReq);

                        return;
                    }

                    if (msg.getPerformative() == ACLMessage.INFORM &&
                        "QA_SUBTASK_DONE".equals(ontology)) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = (String) payload.get("run_id");
                        String agent = (String) payload.get("agent");

                        if (payload.get("error") != null) {
                            System.out.println("[QaAgent] Agent failed: " + agent +
                                    " error=" + payload.get("error"));
                        }

                        Set<String> done = pending.computeIfAbsent(runId, k -> new HashSet<>());
                        done.add(agent);

                        // Descomentar quando o outro agente tiver funcionando
                        // if (done.size() >= 2) {
                        if (done.size() >= 1) {
                            MongoDatabase db = MongoHelper.getDatabase();
                            MongoCollection<Document> runs = db.getCollection("run_status");

                            runs.updateOne(
                                new Document("run_id", runId),
                                new Document("$set", new Document("qa_completed_at",
                                        Instant.now().toString()))
                            );

                            Map<String, Object> notifyBody = new HashMap<>();
                            notifyBody.put("type", "QA_COMPLETED");
                            notifyBody.put("run_id", runId);

                            ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                            notify.addReceiver(new AID("llm_agent", AID.ISLOCALNAME));
                            notify.setOntology("QA_COMPLETED");
                            notify.setContent(gson.toJson(notifyBody));
                            send(notify);

                            pending.remove(runId);
                        }

                        return;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
