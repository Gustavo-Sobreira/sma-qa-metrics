package com.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class SonarQubeAgent extends Agent {
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " started (SonarQubeAgent)");

        addBehaviour(new jade.core.behaviours.CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    try {
                        if ("JSON".equalsIgnoreCase(msg.getLanguage())) {
                            Map<?,?> payload = mapper.readValue(msg.getContent(), Map.class);
                            handleRequest(msg.getSender().getLocalName(), payload, msg.getReplyWith());
                        } else {
                            System.out.println("SonarQubeAgent: unsupported language: " + msg.getLanguage());
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

    private void handleRequest(String requester, Map<?,?> payload, String replyWith) {
        CompletableFuture.runAsync(() -> {
            try {
                String repoDir = (String) payload.get("repoDir");
                String commitHash = (String) payload.get("commitHash");

                Map<String,Object> result = new HashMap<>();
                result.put("tool", "sonarqube");
                result.put("repoDir", repoDir);
                result.put("commitHash", commitHash);
                result.put("timestamp", System.currentTimeMillis());

                String sonarHost = System.getenv("SONAR_HOST_URL");
                String sonarToken = System.getenv("SONAR_LOGIN");

                if (sonarToken == null || sonarToken.isEmpty() || sonarHost == null || sonarHost.isEmpty()) {
                    result.put("status", "simulated");
                    result.put("note", "SONAR_HOST_URL or SONAR_LOGIN not configured; returning simulated metrics");
                    Map<String,Object> measures = new HashMap<>();
                    measures.put("bugs", 0);
                    measures.put("vulnerabilities", 0);
                    measures.put("code_smells", 0);
                    measures.put("coverage", 0.0);
                    result.put("measures", measures);
                } else {
                    Path work = Paths.get(repoDir);
                    File tmpReport = work.resolve("sonar-scanner-output.txt").toFile();
                    List<String> cmd = new ArrayList<>();
                    cmd.add("sonar-scanner");
                    cmd.add("-Dsonar.host.url=" + sonarHost);
                    cmd.add("-Dsonar.login=" + sonarToken);
                    String projectKey = work.getFileName().toString() + "-" + (commitHash != null && commitHash.length() >= 7 ? commitHash.substring(0,7) : UUID.randomUUID().toString());
                    cmd.add("-Dsonar.projectKey=" + projectKey);

                    ProcessBuilder pb = new ProcessBuilder(cmd);
                    pb.directory(work.toFile());
                    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(tmpReport));
                    pb.redirectErrorStream(true);

                    try {
                        Process p = pb.start();
                        int rc = p.waitFor(20, TimeUnit.MINUTES) ? p.exitValue() : -1;
                        result.put("scanner_exit_code", rc);
                    } catch (IOException | InterruptedException e) {
                        try {
                            String dockerCmd = String.format("docker run --rm -v %s:/usr/src sonarsource/sonar-scanner-cli -Dsonar.host.url=%s -Dsonar.login=%s -Dsonar.projectBaseDir=/usr/src -Dsonar.projectKey=%s",
                                    work.toAbsolutePath().toString(), sonarHost, sonarToken, projectKey);
                            ProcessBuilder pb2 = new ProcessBuilder("bash", "-c", dockerCmd);
                            pb2.redirectOutput(ProcessBuilder.Redirect.appendTo(tmpReport));
                            pb2.redirectErrorStream(true);
                            Process p2 = pb2.start();
                            int rc2 = p2.waitFor(20, TimeUnit.MINUTES) ? p2.exitValue() : -1;
                            result.put("scanner_exit_code_docker", rc2);
                        } catch (Exception ex) {
                            result.put("scanner_error", ex.getMessage());
                        }
                    }

                    try {
                        String apiUrl = sonarHost + "/api/measures/component?component=" + projectKey + "&metricKeys=bugs,vulnerabilities,code_smells,coverage";
                        List<String> curlCmd = new ArrayList<>();
                        curlCmd.add("curl");
                        curlCmd.add("-s");
                        curlCmd.add("-u");
                        curlCmd.add(sonarToken + ":");
                        curlCmd.add(apiUrl);
                        ProcessBuilder curlPb = new ProcessBuilder(curlCmd);
                        Process curlP = curlPb.start();
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        try (InputStream is = curlP.getInputStream()) {
                            is.transferTo(baos);
                        }
                        curlP.waitFor();
                        String sonarJson = baos.toString();
                        result.put("status", "ok");
                        result.put("api_response", mapper.readValue(sonarJson, Map.class));
                    } catch (Exception e) {
                        result.put("status", "ok");
                        result.put("api_error", e.getMessage());
                    }
                }

                ACLMessage reply = new ACLMessage(ACLMessage.INFORM);
                reply.addReceiver(new AID("qaAgent", AID.ISLOCALNAME));
                reply.setLanguage("JSON");
                reply.setContent(mapper.writeValueAsString(result));
                send(reply);

                System.out.println("SonarQubeAgent: analysis finished for repo=" + repoDir + " commit=" + commitHash);

            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
