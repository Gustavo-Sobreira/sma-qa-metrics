package com.agents;

import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import java.sql.*;
import java.util.*;

/**
 * MetricsAgent: recebe git log (texto), analisa quantidade de commits por autor e salva em commit_metrics.
 */
public class MetricsAgent extends Agent {
    private Connection conn;

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " started (MetricsAgent)");
        try {
            String url = System.getenv("POSTGRES_URL");
            String user = System.getenv("POSTGRES_USER");
            String pass = System.getenv("POSTGRES_PASSWORD");
            conn = DriverManager.getConnection(url, user, pass);
        } catch (Exception e) { e.printStackTrace(); }

        addBehaviour(new jade.core.behaviours.CyclicBehaviour(this) {
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    if (msg.getContent() != null) {
                        try {
                            handleLog(msg.getContent());
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                } else {
                    block();
                }
            }
        });
    }

    private void handleLog(String gitLog) throws Exception {
        System.out.println("MetricsAgent received gitLog (len=" + (gitLog==null?0:gitLog.length()) + ")");
        Map<String,Integer> counts = new HashMap<>();
        if (gitLog != null) {
            String[] lines = gitLog.split("\\n");
            for (String l : lines) {
                if (l.trim().isEmpty()) continue;
                String[] parts = l.split("\\|\\|\\|");
                if (parts.length >= 2) {
                    String author = parts[1];
                    counts.put(author, counts.getOrDefault(author, 0) + 1);
                }
            }
        }

        for (Map.Entry<String,Integer> e : counts.entrySet()) {
            PreparedStatement ps = conn.prepareStatement("INSERT INTO commit_metrics(repo, author, commits_count) VALUES(?,?,?)");
            ps.setString(1, "repo");
            ps.setString(2, e.getKey());
            ps.setInt(3, e.getValue());
            ps.executeUpdate();
            System.out.println("Saved metric: " + e.getKey() + " -> " + e.getValue());
        }

        System.out.println("MetricsAgent finished analysis: " + counts.size() + " authors.");
    }

    @Override
    protected void takeDown() {
        try { if (conn != null) conn.close(); } catch (Exception e){}
    }
}
