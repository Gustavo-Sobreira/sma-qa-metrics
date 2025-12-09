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

import java.time.Instant;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public class QaAgent extends Agent {

    private final Gson gson = new Gson();

    @Override
    protected void setup() {

        System.out.println("[QaAgent] Ready.");

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {

            private final Set<String> completedAgents = new HashSet<>();
            private String currentRunId = null;
            private String currentRepoPath = null;

            @Override
            public void action() {

                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }

                try {
                    // ---------------------------------------------------------
                    // 1) Nova execução recebida do CoordinatorAgent
                    // ---------------------------------------------------------
                    if (msg.getPerformative() == ACLMessage.INFORM &&
                            "Repo-Cloned".equals(msg.getOntology())) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String repoPath = (String) payload.get("repoPath");

                        System.out.println("[QaAgent] Repositório recebido: " + repoPath);

                        ACLMessage sonarReq = new ACLMessage(ACLMessage.REQUEST);
                        sonarReq.addReceiver(new AID("sonar_agent", AID.ISLOCALNAME));
                        sonarReq.setOntology("RUN_SONAR");
                        sonarReq.setContent(gson.toJson(Map.of("repo_path", repoPath)));
                        send(sonarReq);

                        ACLMessage phpReq = new ACLMessage(ACLMessage.REQUEST);
                        phpReq.addReceiver(new AID("php_agent", AID.ISLOCALNAME));
                        phpReq.setOntology("RUN_PHPMETRICS");
                        phpReq.setContent(gson.toJson(Map.of("repo_path", repoPath)));
                        send(phpReq);

                        return;
                    }

                    // ---------------------------------------------------------
                    // 2) Recebendo respostas de SonarAgent e PhpAgent
                    // ---------------------------------------------------------
                    if (msg.getPerformative() == ACLMessage.INFORM &&
                            msg.getOntology() != null &&
                            msg.getOntology().equals("QA_SUBTASK_DONE")) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = (String) payload.get("run_id");
                        String agentName = (String) payload.get("agent");

                        if (runId.equals(currentRunId)) {
                            completedAgents.add(agentName);
                            System.out.println("[QaAgent] Subtarefa concluída: " + agentName);
                        }

                        // -----------------------------------------------------
                        // 3) Verificar se TODOS agentes concluíram
                        // -----------------------------------------------------
                        if (completedAgents.contains("sonar") &&
                                completedAgents.contains("php")) {

                            System.out.println("[QaAgent] ALL DONE for run " + currentRunId);

                            // 3.1 Atualizar Mongo
                            MongoDatabase db = MongoHelper.getDatabase();
                            MongoCollection<Document> runs = db.getCollection("run_status");

                            runs.updateOne(
                                    new Document("run_id", currentRunId),
                                    new Document("$set", new Document("qa_completed_at",
                                            Instant.now().toString())));

                            // 3.2 Notificar LlmAgent
                            ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                            notify.addReceiver(new AID("LlmAgent", AID.ISLOCALNAME));
                            notify.setOntology("QA_COMPLETED");
                            notify.setContent(gson.toJson(Map.of(
                                    "type", "QA_COMPLETED",
                                    "run_id", currentRunId)));
                            send(notify);

                            // Reset
                            completedAgents.clear();
                            currentRunId = null;
                            currentRepoPath = null;
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
