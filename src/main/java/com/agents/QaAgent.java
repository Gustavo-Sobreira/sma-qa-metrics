package com.agents;

import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.*;
import java.io.*;
import java.util.*;

/**
 * QaAgent: recebe repoDir, commitHash e changedFiles, executa phpmetrics e sonar-scanner,
 * salva resultados em PostgreSQL (tabela qa_results) e gera logs.
 */
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
            public void action() {
                ACLMessage msg = receive();
                if (msg != null) {
                    if ("JSON".equalsIgnoreCase(msg.getLanguage())) {
                        try {
                            Map content = mapper.readValue(msg.getContent(), Map.class);
                            handleQa((String)content.get("repoDir"), (String)content.get("commitHash"), (List)content.get("changedFiles"));
                        } catch (Exception e) { e.printStackTrace(); }
                    }
                } else {
                    block();
                }
            }
        });
    }

    private void handleQa(String repoDir, String commitHash, List changedFiles) throws Exception {
        System.out.println("QaAgent analyzing commit=" + commitHash + " in " + repoDir + ", files=" + changedFiles);

        File work = new File(repoDir);
        File phpmetricsReport = new File(work, "phpmetrics-report.json");
        try {
            ProcessBuilder pb = new ProcessBuilder("phpmetrics", "--report-json=" + phpmetricsReport.getAbsolutePath(), repoDir);
            pb.directory(work);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = br.readLine()) != null) System.out.println("phpmetrics> " + line);
            }
            p.waitFor();
        } catch (Exception e) {
            System.out.println("phpmetrics execution failed: " + e.getMessage());
        }

        File sonarReportFile = new File(work, "sonar-report.txt");
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("sonar-scanner");
            cmd.add("-Dsonar.host.url=" + System.getenv("SONAR_HOST_URL"));
            String token = System.getenv("SONAR_LOGIN");
            if (token != null && !token.isEmpty()) {
                cmd.add("-Dsonar.login=" + token);
            }
            cmd.add("-Dsonar.projectKey=" + (new File(repoDir)).getName() + "-" + commitHash.substring(0,7));
            ProcessBuilder pb2 = new ProcessBuilder(cmd);
            pb2.directory(work);
            pb2.redirectOutput(ProcessBuilder.Redirect.appendTo(sonarReportFile));
            pb2.redirectErrorStream(true);
            Process p2 = pb2.start();
            p2.waitFor();
        } catch (Exception e) {
            System.out.println("sonar-scanner failed: " + e.getMessage());
        }

        try {
            if (phpmetricsReport.exists()) {
                String json = new String(java.nio.file.Files.readAllBytes(phpmetricsReport.toPath()));
                PreparedStatement ps = conn.prepareStatement("INSERT INTO qa_results(repo, commit_hash, tool, report) VALUES(?,?,?,?::jsonb)");
                ps.setString(1, repoDir);
                ps.setString(2, commitHash);
                ps.setString(3, "phpmetrics");
                ps.setString(4, json);
                ps.executeUpdate();
                System.out.println("Saved phpmetrics result to DB");
            } else {
                System.out.println("phpmetrics report not found: " + phpmetricsReport.getAbsolutePath());
            }
        } catch (Exception e) { e.printStackTrace(); }

        try {
            String projectKey = (new File(repoDir)).getName() + "-" + commitHash.substring(0,7);
            String sonarHost = System.getenv("SONAR_HOST_URL");
            String token = System.getenv("SONAR_LOGIN");
            if (sonarHost != null && !sonarHost.isEmpty()) {
                String apiUrl = sonarHost + "/api/measures/component?component=" + projectKey + "&metricKeys=code_smells,bugs,vulnerabilities,coverage";
                List<String> curlCmd = new ArrayList<>();
                curlCmd.add("curl");
                curlCmd.add("-s");
                if (token != null && !token.isEmpty()) {
                    curlCmd.add("-u");
                    curlCmd.add(token + ":");
                }
                curlCmd.add(apiUrl);
                ProcessBuilder curlPb = new ProcessBuilder(curlCmd);
                Process p3 = curlPb.start();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (InputStream is = p3.getInputStream()) {
                    is.transferTo(baos);
                }
                p3.waitFor();
                String sonarJson = baos.toString();
                PreparedStatement ps2 = conn.prepareStatement(
                    "INSERT INTO qa_results(repo, commit_hash, tool, report) VALUES(?,?,?,?::jsonb)"
                );
                ps2.setString(1, repoDir);
                ps2.setString(2, commitHash);
                ps2.setString(3, "sonarqube");
                ps2.setString(4, sonarJson);
                ps2.executeUpdate();

                System.out.println("Saved Sonar measures to DB");
            } else {
                System.out.println("SONAR_HOST_URL not configured; skipping Sonar API fetch");
            }
        } catch (Exception e) { e.printStackTrace(); }

        System.out.println("QA finished for commit " + commitHash + ". Files analyzed: " + (changedFiles==null?0:changedFiles.size()));
    }

    @Override
    protected void takeDown() {
        try { if (conn != null) conn.close(); } catch (Exception e){}
    }
}
