package com.agents;

import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;

public class QaAgent extends Agent {
    private final ObjectMapper mapper = new ObjectMapper();
    private Connection conn;

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " started (QaAgent)");
        try {
            String url = System.getenv("POSTGRES_URL");
            String user = System.getenv("POSTGRES_USER");
            String pass = System.getenv("POSTGRES_PASSWORD");
            conn = DriverManager.getConnection(url, user, pass);
        } catch (Exception e) {
            e.printStackTrace();
        }

        addBehaviour(new jade.core.behaviours.CyclicBehaviour(this) {
            @Override
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    try {
                        if ("JSON".equalsIgnoreCase(msg.getLanguage())) {
                            Map<?,?> content = mapper.readValue(msg.getContent(), Map.class);
                            handleQa(content);
                        } else if (msg.getPerformative() == ACLMessage.INFORM) {
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

    private void handleQa(Map<?,?> payload) throws Exception {
        String repoDir = (String) payload.get("repoDir");
        String commitHash = (String) payload.get("commitHash");
        Object changedFiles = payload.get("changedFiles");

        System.out.println("QaAgent: received work for repo=" + repoDir + " commit=" + commitHash);

        ACLMessage sonarReq = new ACLMessage(ACLMessage.REQUEST);
        sonarReq.addReceiver(new AID("sonarqubeAgent", AID.ISLOCALNAME));
        sonarReq.setLanguage("JSON");
        sonarReq.setContent(mapper.writeValueAsString(payload));
        send(sonarReq);

        ACLMessage phpReq = new ACLMessage(ACLMessage.REQUEST);
        phpReq.addReceiver(new AID("phpmetricsAgent", AID.ISLOCALNAME));
        phpReq.setLanguage("JSON");
        phpReq.setContent(mapper.writeValueAsString(payload));
        send(phpReq);

        Map<String,Map> toolResults = new HashMap<>();
        long start = System.currentTimeMillis();
        long timeoutMs = 20 * 60 * 1000; 
        while (System.currentTimeMillis() - start < timeoutMs && toolResults.size() < 2) {
            ACLMessage reply = blockingReceive(5000);
            if (reply == null) continue;
            try {
                if ("JSON".equalsIgnoreCase(reply.getLanguage())) {
                    Map<?,?> data = mapper.readValue(reply.getContent(), Map.class);
                    String tool = (String) data.get("tool");
                    if (tool == null) {
                        if (data.containsKey("api_response") || data.containsKey("measures")) tool = "sonarqube";
                        else tool = "phpmetrics";
                    }
                    toolResults.put(tool, (Map) data);
                    System.out.println("QaAgent: received result from tool=" + tool);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        try {
            if (conn != null) {
                String insertSql = "INSERT INTO qa_results(repo, commit_hash, tool, report, created_at) VALUES(?,?,?,?::jsonb,?)";
                PreparedStatement ps = conn.prepareStatement(insertSql);
                for (Map.Entry<String,Map> e : toolResults.entrySet()) {
                    ps.setString(1, repoDir);
                    ps.setString(2, commitHash);
                    ps.setString(3, e.getKey());
                    ps.setString(4, mapper.writeValueAsString(e.getValue()));
                    ps.setTimestamp(5, new Timestamp(System.currentTimeMillis()));
                    ps.executeUpdate();
                }
            } else {
                System.out.println("QaAgent: DB connection is null; skipping persistence");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        System.out.println("QaAgent: finished processing repo=" + repoDir);
    }

    @Override
    protected void takeDown() {
        try { if (conn != null) conn.close(); } catch (Exception e){}
    }
}
