package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;

public class SonarAgent extends Agent {

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final String WORKER_URL = System.getenv().getOrDefault("SONAR_WORKER_URL", "http://sonar-worker:9100/scan");

    @Override
    protected void setup() {
        System.out.println("[SonarAgent] Ready.");

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }

                try {
                    if (msg.getPerformative() == ACLMessage.REQUEST && "RUN_SONAR".equals(msg.getOntology())) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = (String) payload.get("run_id");
                        String repoPath = (String) payload.get("repo_path");

                        System.out.println("[SonarAgent] Running sonar for run=" + runId + " path=" + repoPath);

                        // call worker
                        Map<String, Object> body = new HashMap<>();
                        body.put("run_id", runId);
                        body.put("repo_path", repoPath);

                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create("http://sonar-worker:9100/scan"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        gson.toJson(Map.of(
                                                "repo_path", repoPath,
                                                "run_id", runId
                                        ))
                                ))
                                .build();

                        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());

                        System.out.println("[SonarAgent] Worker status: " + resp.statusCode());
                        System.out.println("[SonarAgent] Worker body: " + resp.body());

                        // notify QaAgent
                        Map<String, Object> done = new HashMap<>();
                        done.put("run_id", runId);
                        done.put("agent", "sonar");

                        ACLMessage doneMsg = new ACLMessage(ACLMessage.INFORM);
                        doneMsg.addReceiver(new AID("qa_agent", AID.ISLOCALNAME));
                        doneMsg.setOntology("QA_SUBTASK_DONE");
                        doneMsg.setContent(gson.toJson(done));
                        send(doneMsg);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}