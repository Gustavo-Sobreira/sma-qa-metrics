package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import org.bson.Document;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.*;

/**
 * GitLogAgent
 * - Varre o git log do projeto
 * - Calcula métricas necessárias (PoC: LOC/NALOC por commit via numstat)
 * - Salva no Mongo
 * - Dá ok ao CoordinatorAgent (GIT_DONE)
 *
 * MELHORIA:
 * - Em erro: GIT_FAILED
 */
public class GitLogAgent extends Agent {

    private final Gson gson = GsonProvider.get();

    @Override
    protected void setup() {
        AgentDirectory.register(this, "git-log", "git-log");

        System.out.println("[ 📟 - GitLogAgent ]\n     |->  Pronto.");

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                MessageTemplate mt = MessageTemplate.MatchPerformative(ACLMessage.INFORM);
                ACLMessage msg = myAgent.receive(mt);

                if (msg == null) {
                    block();
                    return;
                }

                if (!"REPO_READY".equals(msg.getOntology())) {
                    return;
                }

                Map payload = gson.fromJson(msg.getContent(), Map.class);
                String runId = String.valueOf(payload.get("run_id"));
                String repoPath = String.valueOf(payload.get("repo_path"));

                try {
                    System.out.println("[ 📟 - GitLogAgent - ⌛]\n     |->  Processando métricas de repositório (run=" + runId + ")");

                    Map<String, Object> locMetrics = calcularLOCporCommit(repoPath);

                    boolean inserted = false;
                    MongoDatabase db = MongoHelper.getDatabase();
                    MongoCollection<Document> col = db.getCollection("git_metrics");

                    Document metrics = new Document()
                            .append("run_id", runId)
                            .append("repo_path", repoPath)
                            .append("loc_metrics", locMetrics)
                            .append("generated_at", Instant.now().toString());

                    col.insertOne(metrics);
                    inserted = true;

                    db.getCollection("run_status").updateOne(new Document("run_id", runId),
                            new Document("$set",
                                    new Document("git_metrics_done", true)
                                            .append("git_metrics_finished_at", Instant.now().toString())
                                            .append("git_ok", true)));

                    System.out.println("[ 📟 - GitLogAgent ] Métricas inseridas no Mongo (run=" + runId + ")");

                    ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                    notify.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
                    notify.setOntology("GIT_DONE");
                    notify.setContent(gson.toJson(Map.of("run_id", runId, "ok", inserted)));
                    send(notify);

                } catch (Exception e) {
                    System.out.println("[ 📟 - GitLogAgent ] Exceção no processamento: " + e.getMessage());
                    e.printStackTrace();

                    try {
                        MongoDatabase db = MongoHelper.getDatabase();
                        db.getCollection("run_status").updateOne(new Document("run_id", runId),
                                new Document("$set",
                                        new Document("git_ok", false)
                                                .append("git_failed_at", Instant.now().toString())
                                                .append("git_failed_reason", e.getMessage())));
                    } catch (Exception ignored) {}

                    ACLMessage fail = new ACLMessage(ACLMessage.INFORM);
                    fail.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
                    fail.setOntology("GIT_FAILED");
                    fail.setContent(gson.toJson(Map.of(
                            "run_id", runId,
                            "reason", e.getMessage()
                    )));
                    send(fail);
                }
            }
        });
    }

    private Map<String, Object> calcularLOCporCommit(String repoDir) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> commits = new ArrayList<>();

        try {
            Process process = new ProcessBuilder(
                    "bash", "-lc",
                    "git -C " + escapeBash(repoDir) + " log --numstat --pretty=format:'--commit--%H'"
            ).start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                String currentCommit = null;
                int locSum = 0;
                int nalocSum = 0;

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("--commit--")) {
                        if (currentCommit != null) {
                            commits.add(Map.of("commit", currentCommit, "LOC", locSum, "NALOC", nalocSum));
                        }
                        currentCommit = line.replace("--commit--", "").trim();
                        locSum = 0;
                        nalocSum = 0;
                        continue;
                    }

                    String[] parts = line.split("\t");
                    if (parts.length == 3) {
                        try {
                            int added = Integer.parseInt(parts[0]);
                            int removed = Integer.parseInt(parts[1]);
                            locSum += (added + removed);
                            nalocSum += added;
                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (currentCommit != null) {
                    commits.add(Map.of("commit", currentCommit, "LOC", locSum, "NALOC", nalocSum));
                }
            }

            result.put("commits", commits);

        } catch (Exception e) {
            result.put("error", e.getMessage());
        }

        return result;
    }

    private String escapeBash(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
