package com.agents;

import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.core.AID;
import jade.core.behaviours.TickerBehaviour;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.diff.DiffFormatter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * CoordinatorAgent — recebe webhook, clona o repo, coleta dados,
 * envia para QaAgent (changed files) e MetricsAgent (git log).
 */
public class CoordinatorAgent extends Agent {

    private static final ConcurrentLinkedQueue<ACLMessage> inbox = new ConcurrentLinkedQueue<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public static void enqueueMessage(ACLMessage message) {
        inbox.add(message);
    }

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " started (CoordinatorAgent)");

        addBehaviour(new TickerBehaviour(this, 2000) {
            @Override
            protected void onTick() {
                ACLMessage m = inbox.poll();
                if (m != null) {
                    try {
                        handleWebhook(m.getContent());
                    } catch (Exception e) {
                        System.out.println("ERROR processing webhook:");
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    private void handleWebhook(String jsonContent) throws Exception {
        System.out.println("Coordinator received webhook: " + jsonContent);

        // -------- VALIDAR PAYLOAD --------
        Map<String, Object> payload = mapper.readValue(jsonContent, Map.class);

        Object repoObj = payload.get("repository");
        if (repoObj == null) {
            System.out.println("❌ ERROR: Payload missing 'repository'");
            return;
        }

        String repoUrl = repoObj.toString().trim();
        if (repoUrl.isBlank()) {
            System.out.println("❌ ERROR: Repository URL empty.");
            return;
        }

        // -------- NORMALIZAR URL --------
        // Se for SSH (git@github.com:user/repo.git)
        if (repoUrl.startsWith("git@")) {
            // Remove "git@" e transforma no formato HTTPS
            // git@github.com:user/repo.git → https://github.com/user/repo.git
            repoUrl = repoUrl.replace("git@", "https://");
            repoUrl = repoUrl.replace("github.com:", "github.com/");
        }

        // Se não terminar com .git, adiciona
        if (!repoUrl.endsWith(".git")) {
            repoUrl = repoUrl + ".git";
        }

        System.out.println("✔ Normalized repository URL = " + repoUrl);

        // -------- CRIAR DIRETÓRIO TEMP --------
        Path tmp = Files.createTempDirectory("repo-");
        System.out.println("📁 Cloning into: " + tmp);

        // -------- CLONAR --------
        Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(tmp.toFile())
                .call();

        // -------- IDENTIFICAR COMMIT --------
        String commitHash = (String) payload.get("commit");

        if (commitHash == null) {
            Iterable<RevCommit> commits = git.log().setMaxCount(1).call();
            RevCommit latest = commits.iterator().next();
            commitHash = latest.getName();
        }

        RevCommit commit = git.log().setMaxCount(1).call().iterator().next();

        // -------- LISTAR ARQUIVOS ALTERADOS --------
        List<String> changedFiles = new ArrayList<>();

        if (commit.getParentCount() > 0) {
            RevCommit parent = commit.getParent(0);

            DiffFormatter df = new DiffFormatter(System.out);
            df.setRepository(git.getRepository());

            List<org.eclipse.jgit.diff.DiffEntry> diffs =
                    df.scan(parent.getTree(), commit.getTree());

            for (org.eclipse.jgit.diff.DiffEntry d : diffs) {
                changedFiles.add(d.getNewPath());
            }

            df.close();
        } else {
            // Se for o primeiro commit
            Files.walk(tmp)
                    .filter(Files::isRegularFile)
                    .forEach(p -> changedFiles.add(tmp.relativize(p).toString()));
        }

        // -------- GERAR git log --------
        StringBuilder gitLogBuilder = new StringBuilder();
        for (RevCommit rc : git.log().call()) {
            gitLogBuilder.append(rc.getName()).append("|||")
                    .append(rc.getAuthorIdent().getName()).append("|||")
                    .append(rc.getFullMessage().replace("\n", " "))
                    .append("\n");
        }
        String gitLog = gitLogBuilder.toString();

        // -------- ENVIAR PARA QA --------
        Map<String, Object> toQa = new HashMap<>();
        toQa.put("repoDir", tmp.toString());
        toQa.put("commitHash", commitHash);
        toQa.put("changedFiles", changedFiles);

        ACLMessage msgQa = new ACLMessage(ACLMessage.REQUEST);
        msgQa.addReceiver(new AID("qaAgent", AID.ISLOCALNAME));
        msgQa.setLanguage("JSON");
        msgQa.setContent(mapper.writeValueAsString(toQa));
        send(msgQa);

        // -------- ENVIAR PARA METRICS --------
        ACLMessage msgMetrics = new ACLMessage(ACLMessage.INFORM);
        msgMetrics.addReceiver(new AID("metricsAgent", AID.ISLOCALNAME));
        msgMetrics.setLanguage("TEXT");
        msgMetrics.setContent(gitLog);
        send(msgMetrics);

        System.out.println("✔ Coordinator dispatched messages");
        System.out.println("  → Changed files: " + changedFiles.size());
        System.out.println("  → GitLog length: " + gitLog.length());
    }
}
