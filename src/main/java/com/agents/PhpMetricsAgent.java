package com.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class PhpMetricsAgent extends Agent {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " started (PhpMetricsAgent)");

        addBehaviour(new jade.core.behaviours.CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    try {
                        if ("JSON".equalsIgnoreCase(msg.getLanguage())) {
                            Map<?,?> payload = mapper.readValue(msg.getContent(), Map.class);
                            handleRequest(payload);
                        } else {
                            System.out.println("PhpMetricsAgent: unsupported language: " + msg.getLanguage());
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

    private void handleRequest(Map<?,?> payload) {
        CompletableFuture.runAsync(() -> {
            try {
                String repoDir = (String) payload.get("repoDir");
                String commitHash = (String) payload.get("commitHash");

                Map<String,Object> result = new HashMap<>();
                result.put("tool", "phpmetrics");
                result.put("repoDir", repoDir);
                result.put("commitHash", commitHash);
                result.put("timestamp", System.currentTimeMillis());

                Path work = Paths.get(repoDir);
                File reportFile = work.resolve("phpmetrics-report.json").toFile();

                try {
                    List<String> cmd = Arrays.asList("phpmetrics", "--report-json=" + reportFile.getAbsolutePath(), repoDir);
                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(work.toFile());
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            System.out.println("phpmetrics> " + line);
                        }
                    }
                    p.waitFor(10, TimeUnit.MINUTES);
                } catch (Exception e) {
                    try {
                        String dockerCmd = String.format("docker run --rm -v %s:/project phpmetrics/phpmetrics --report-json=/project/phpmetrics-report.json /project",
                                work.toAbsolutePath().toString());
                        ProcessBuilder pb2 = new ProcessBuilder("bash", "-c", dockerCmd);
                        pb2.directory(work.toFile());
                        pb2.redirectErrorStream(true);
                        Process p2 = pb2.start();
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(p2.getInputStream()))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                System.out.println("phpmetrics(docker)> " + line);
                            }
                        }
                        p2.waitFor(10, TimeUnit.MINUTES);
                    } catch (Exception ex) {
                        result.put("status", "simulated");
                        result.put("note", "phpmetrics execution failed: " + ex.getMessage());
                    }
                }

                if (reportFile.exists()) {
                    try {
                        String json = new String(Files.readAllBytes(reportFile.toPath()));
                        result.put("status", "ok");
                        result.put("report", mapper.readValue(json, Map.class));
                    } catch (Exception e) {
                        result.put("report_read_error", e.getMessage());
                    }
                } else if (!result.containsKey("status")) {
                    result.put("status", "simulated");
                    result.put("note", "no phpmetrics report produced");
                }

                ACLMessage reply = new ACLMessage(ACLMessage.INFORM);
                reply.addReceiver(new AID("qaAgent", AID.ISLOCALNAME));
                reply.setLanguage("JSON");
                reply.setContent(mapper.writeValueAsString(result));
                send(reply);

                System.out.println("PhpMetricsAgent: finished for repo=" + repoDir);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
