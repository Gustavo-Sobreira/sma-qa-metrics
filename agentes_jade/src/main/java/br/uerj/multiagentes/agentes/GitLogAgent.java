package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

import org.bson.Document;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.*;

public class GitLogAgent extends Agent {

    private final Gson gson = GsonProvider.get();

    @Override
    protected void setup() {

        AgentDirectory.register(this, "git-log", "git-log");

        addBehaviour(new CyclicBehaviour() {

            public void action() {

                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }

                if (msg.getPerformative() != ACLMessage.REQUEST ||
                        !"RUN_PROJECT_ANALYZER".equals(msg.getOntology())) {
                    return;
                }

                Map payload = gson.fromJson(msg.getContent(), Map.class);

                String run_id = String.valueOf(payload.get("run_id"));
                String repo_path = String.valueOf(payload.get("repo_path"));
                log(run_id, "RECEIVED", "RUN_PROJECT_ANALYZER", payload);

                if (isRunFailed(run_id)) {
                    log(run_id, "IGNORED_AFTER_FAILED", "RUN_PROJECT_ANALYZER", payload);
                    return;
                }

                try {

                    Map<String, Object> metrics = calcularLOCporCommit(repo_path);

                    MongoDatabase db = MongoHelper.getDatabase();
                    MongoCollection<Document> col = db.getCollection("projectsReport");

                    Document doc = new Document()
                            .append("run_id", run_id)
                            .append("repo_path", repo_path)
                            .append("git_log_metrics", metrics)
                            .append("git_log_at", Instant.now().toString());

                    col.updateOne(
                            new Document("run_id", run_id),
                            new Document("$set", doc),
                            new com.mongodb.client.model.UpdateOptions().upsert(true)
                    );

                    ACLMessage done = new ACLMessage(ACLMessage.INFORM);
                    done.addReceiver(new AID("project_analyzer_agent", AID.ISLOCALNAME));
                    done.setOntology("QA_SUBTASK_DONE");
                    done.setContent(gson.toJson(Map.of(
                            "run_id", run_id,
                            "agent", "git-log",
                            "ok", true
                    )));

                    send(done);
                    log(run_id, "SENT", "QA_SUBTASK_DONE", Map.of("agent", "git-log"));

                } catch (Exception e) {

                    e.printStackTrace();

                    ACLMessage fail = new ACLMessage(ACLMessage.INFORM);
                    fail.addReceiver(new AID("project_analyzer_agent", AID.ISLOCALNAME));
                    fail.setOntology("QA_SUBTASK_FAILED");
                    fail.setContent(gson.toJson(Map.of(
                            "run_id", run_id,
                            "agent", "git-log",
                            "reason", e.getMessage()
                    )));

                    send(fail);
                    log(run_id, "SENT", "QA_SUBTASK_FAILED",
                            Map.of("agent", "git-log", "reason", e.getMessage() == null ? "unknown" : e.getMessage()));
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
                    "git -C " + escapeBash(repoDir) +
                            " log -n 100 --numstat --pretty=format:'--commit--%H|%ct'")
                    .start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {

                String line;
                String currentCommit = null;
                int locSum = 0;
                int nalocSum = 0;
                long ts = 0;

                while ((line = reader.readLine()) != null) {

                    if (line.startsWith("--commit--")) {

                        if (currentCommit != null) {
                            commits.add(Map.of(
                                    "commit", currentCommit,
                                    "LOC", locSum,
                                    "NALOC", nalocSum,
                                    "timestamp", ts
                            ));
                        }

                        String[] parts = line.replace("--commit--", "").split("\\|");
                        currentCommit = parts[0];
                        ts = parts.length > 1 ? Long.parseLong(parts[1]) : 0;

                        locSum = 0;
                        nalocSum = 0;
                        continue;
                    }

                    String[] parts = line.split("\t");

                    if (parts.length == 3) {
                        try {
                            int add = Integer.parseInt(parts[0]);
                            int rem = Integer.parseInt(parts[1]);

                            locSum += (add + rem);
                            nalocSum += add;

                        } catch (NumberFormatException ignored) {}
                    }
                }

                if (currentCommit != null) {
                    commits.add(Map.of(
                            "commit", currentCommit,
                            "LOC", locSum,
                            "NALOC", nalocSum,
                            "timestamp", ts
                    ));
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

    private boolean isRunFailed(String run_id) {
        try {
            Document status = MongoHelper.getDatabase()
                    .getCollection("runStatus")
                    .find(new Document("run_id", run_id))
                    .first();

            return status != null &&
                    (Boolean.TRUE.equals(status.getBoolean("failed")) || "FAILED".equals(status.getString("stage")));
        } catch (Exception e) {
            return false;
        }
    }

    private void log(String run_id, String event, String ontology, Object data) {
        try {
            MongoHelper.getDatabase().getCollection("logs").insertOne(new Document()
                    .append("run_id", run_id)
                    .append("agent", "git-log")
                    .append("event", event)
                    .append("ontology", ontology)
                    .append("data", data == null ? new Document() : Document.parse(gson.toJson(data)))
                    .append("created_at", Instant.now().toString()));
        } catch (Exception ignored) {}
    }
}
