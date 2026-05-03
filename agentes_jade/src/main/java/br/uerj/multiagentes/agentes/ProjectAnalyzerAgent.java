package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.UpdateOptions;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

import org.bson.Document;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProjectAnalyzerAgent extends Agent {

    private final Gson gson = GsonProvider.get();

    private final Map<String, Set<String>> doneAgents = new ConcurrentHashMap<>();
    private final Set<String> failedRuns = ConcurrentHashMap.newKeySet();

    private static final Set<String> REQUIRED_AGENTS = Set.of("git-log");

    @Override
    protected void setup() {

        AgentDirectory.register(this, "project_analyzer", "project_analyzer");

        addBehaviour(new CyclicBehaviour() {

            public void action() {

                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }

                try {

                    String ontology = msg.getOntology();

                    if ("START_PROJECT_ANALYSIS".equals(ontology)) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String run_id = String.valueOf(payload.get("run_id"));

                        log(run_id, "RECEIVED", ontology, payload);
                        if (isRunFailed(run_id)) {
                            log(run_id, "IGNORED_AFTER_FAILED", ontology, payload);
                            return;
                        }

                        String repo_path = String.valueOf(payload.get("repo_path"));

                        doneAgents.put(run_id, ConcurrentHashMap.newKeySet());
                        failedRuns.remove(run_id);

                        update(run_id,
                                "project_stage", "GITLOG_RUNNING",
                                "project_started_at", Instant.now().toString());

                        ACLMessage req = new ACLMessage(ACLMessage.REQUEST);
                        req.setOntology("RUN_PROJECT_ANALYZER");
                        req.setContent(gson.toJson(Map.of(
                                "run_id", run_id,
                                "repo_path", repo_path
                        )));

                        List<AID> agents = AgentDirectory.find(myAgent, "git-log");

                        if (agents != null && !agents.isEmpty())
                            agents.forEach(req::addReceiver);
                        else
                            req.addReceiver(new AID("git_log_agent", AID.ISLOCALNAME));

                        send(req);
                        log(run_id, "SENT", "RUN_PROJECT_ANALYZER", Map.of("repo_path", repo_path));
                        return;
                    }

                    if ("QA_SUBTASK_FAILED".equals(ontology)) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);

                        String run_id = String.valueOf(payload.get("run_id"));
                        String reason = String.valueOf(payload.getOrDefault("reason", "subtask_failed"));

                        log(run_id, "RECEIVED", ontology, payload);
                        if (isRunFailed(run_id)) {
                            log(run_id, "IGNORED_AFTER_FAILED", ontology, payload);
                            return;
                        }

                        failedRuns.add(run_id);

                        update(run_id,
                                "project_stage", "FAILED",
                                "project_failed_at", Instant.now().toString(),
                                "project_reason", reason);

                        sendFailed(run_id, reason);

                        doneAgents.remove(run_id);
                        return;
                    }

                    if ("QA_SUBTASK_DONE".equals(ontology)) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);

                        String run_id = String.valueOf(payload.get("run_id"));
                        String agent = String.valueOf(payload.get("agent"));

                        log(run_id, "RECEIVED", ontology, payload);
                        if (isRunFailed(run_id)) {
                            log(run_id, "IGNORED_AFTER_FAILED", ontology, payload);
                            return;
                        }

                        boolean ok = Boolean.TRUE.equals(payload.get("ok")) ||
                                "true".equalsIgnoreCase(String.valueOf(payload.get("ok")));

                        if (!ok) {
                            failedRuns.add(run_id);
                            sendFailed(run_id, "Agent failed: " + agent);
                            return;
                        }

                        Set<String> done = doneAgents.computeIfAbsent(run_id, k -> ConcurrentHashMap.newKeySet());
                        done.add(agent);

                        if (done.containsAll(REQUIRED_AGENTS) &&
                                !failedRuns.contains(run_id)) {

                            boolean persisted = persistProjectData(run_id, payload);

                            ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                            notify.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));

                            notify.setOntology(persisted
                                    ? "PROJECT_ANALYZER_DONE"
                                    : "PROJECT_ANALYZER_FAILED");

                            notify.setContent(gson.toJson(Map.of(
                                    "run_id", run_id
                            )));

                            send(notify);
                            log(run_id, "SENT", notify.getOntology(), Map.of("persisted", persisted));

                            update(run_id,
                                    "project_stage", persisted ? "DONE" : "FAILED",
                                    "project_finished_at", Instant.now().toString());
                        }

                        doneAgents.remove(run_id);
                        failedRuns.remove(run_id);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private boolean persistProjectData(String run_id, Map payload) {

        try {
            MongoDatabase db = MongoHelper.getDatabase();
            MongoCollection<Document> col = db.getCollection("projectsReport");

            Document doc = new Document()
                    .append("run_id", run_id)
                    .append("project_analyzer_at", Instant.now().toString())
                    .append("project_analyzer_status", "DONE");

            try {
                doc.append("agent_results", payload);
            } catch (Exception e) {
                doc.append("raw", payload);
            }

            col.updateOne(
                    new Document("run_id", run_id),
                    new Document("$set", doc),
                    new UpdateOptions().upsert(true)
            );

            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void sendFailed(String run_id, String reason) {

        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);

        msg.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
        msg.setOntology("PROJECT_ANALYZER_FAILED");

        msg.setContent(gson.toJson(Map.of(
                "run_id", run_id,
                "reason", reason
        )));

        send(msg);
        log(run_id, "SENT", "PROJECT_ANALYZER_FAILED", Map.of("reason", reason));
    }

    private void update(String run_id, Object... kv) {

        try {
            MongoDatabase db = MongoHelper.getDatabase();
            MongoCollection<Document> col = db.getCollection("projectsReport");

            Document set = new Document();

            for (int i = 0; i < kv.length; i += 2)
                set.append(String.valueOf(kv[i]), kv[i + 1]);

            col.updateOne(
                    new Document("run_id", run_id),
                    new Document("$set", set),
                    new UpdateOptions().upsert(true)
            );

        } catch (Exception ignored) {}
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
                    .append("agent", "project_analyzer")
                    .append("event", event)
                    .append("ontology", ontology)
                    .append("data", data == null ? new Document() : Document.parse(gson.toJson(data)))
                    .append("created_at", Instant.now().toString()));
        } catch (Exception ignored) {}
    }
}
