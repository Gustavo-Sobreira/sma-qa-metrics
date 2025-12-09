package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class SonarAgent extends Agent {

    private final Gson gson = new Gson();

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
                    if (msg.getPerformative() != ACLMessage.REQUEST)
                        return;

                    Map payload = gson.fromJson(msg.getContent(), Map.class);
                    if (payload == null) return;

                    String runId = (String) payload.get("run_id");
                    String repoPath = (String) payload.get("repo_path");

                    System.out.println("[SonarAgent] Running sonar for run=" + runId + " path=" + repoPath);

                    // -------------------------------------
                    // 1) Executar sonar-scanner no container
                    // -------------------------------------
                    String cmd =
                            "docker exec sonar_scanner sonar-scanner " +
                            "-Dsonar.projectBaseDir=/usr/src/repo";

                    System.out.println("[SonarAgent] Exec: " + cmd);

                    ProcessBuilder pb = new ProcessBuilder("bash", "-c", cmd);
                    pb.redirectErrorStream(true);
                    Process process = pb.start();

                    String output = new String(process.getInputStream().readAllBytes());
                    int exit = process.waitFor();

                    System.out.println("[SonarAgent] Scanner output:\n" + output);

                    if (exit != 0) {
                        System.err.println("[SonarAgent] Scanner failed (exit=" + exit + ")");
                    }

                    // -------------------------------------
                    // 2) Enviar resposta para o QaAgent
                    // -------------------------------------
                    ACLMessage reply = new ACLMessage(ACLMessage.INFORM);
                    reply.addReceiver(new AID("QaAgent", AID.ISLOCALNAME));
                    reply.setContent(gson.toJson(Map.of(
                            "run_id", runId,
                            "agent", "sonar",
                            "status", "OK"
                    )));
                    send(reply);

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
