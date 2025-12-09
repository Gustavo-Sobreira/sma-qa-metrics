package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import br.uerj.multiagentes.utils.MongoHelper;
import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import jade.core.behaviours.TickerBehaviour;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class LlmAgent extends Agent {
    private Gson gson = new Gson();

    @Override
    protected void setup() {
        System.out.println("[LlmAgent] Ready.");

        addBehaviour(new TickerBehaviour(this, 10_000) {
            @Override
            protected void onTick() {
                try {
                    processPendingRuns();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive();
                if (msg != null) {
                    try {
                        String content = msg.getContent();
                        System.out.println("[LlmAgent] Received message: " + content);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else {
                    block();
                }
            }
        });
    }

    private void processPendingRuns() {
        MongoDatabase db = MongoHelper.getDatabase();
        MongoCollection<Document> runs = db.getCollection("run_status");

        Document filter = new Document("metrics_done", true)
                .append("llm_done", new Document("$ne", true))
                .append("$or", List.of(new Document("php_done", true), new Document("sonar_done", true)));

        MongoCursor<Document> cursor = runs.find(filter).iterator();
        List<Document> toProcess = new ArrayList<>();
        while (cursor.hasNext()) {
            toProcess.add(cursor.next());
        }
        cursor.close();

        for (Document run : toProcess) {
            try {
                String runId = run.getString("run_id");
                System.out.println("[LlmAgent] Processing run: " + runId);

                MongoCollection<Document> metricsCol = db.getCollection("metrics");
                Document metrics = metricsCol.find(new Document("run_id", runId)).first();

                MongoCollection<Document> phpCol = db.getCollection("phpmetrics_runs");
                Document php = phpCol.find(new Document("run_id", runId)).first();

                MongoCollection<Document> sonarCol = db.getCollection("sonar_runs");
                Document sonar = sonarCol.find(new Document("run_id", runId)).first();

                Document summary = generateSummary(run, metrics, php, sonar);

                MongoCollection<Document> finalCol = db.getCollection("analysis_final");
                Document finalDoc = new Document("run_id", runId)
                        .append("repo", run.getString("repo"))
                        .append("summary", summary.getString("summary_text"))
                        .append("scores", summary.get("scores"))
                        .append("metadata", new Document("metrics", metrics != null)
                                .append("phpmetrics", php != null)
                                .append("sonar", sonar != null))
                        .append("generated_at", Instant.now().toString());
                finalCol.insertOne(finalDoc);

                runs.updateOne(new Document("run_id", runId), new Document("$set",
                        new Document("llm_done", true).append("llm_at", Instant.now().toString())));

                ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                notify.addReceiver(new AID("coordinator", AID.ISLOCALNAME));
                notify.setContent(gson.toJson(Map.of("type", "LLM_SUMMARY_DONE", "run_id", runId)));
                send(notify);

                System.out.println("[LlmAgent] Summary generated and persisted for run=" + runId);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private Document generateSummary(Document run, Document metrics, Document php, Document sonar) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> scores = new HashMap<>();

        sb.append("Run: ").append(run.getString("run_id")).append("");
        sb.append("Repo: ").append(run.getString("repo")).append("");

        if (metrics != null) {
            Integer totalCommits = metrics.getInteger("total_commits", 0);
            sb.append("Total commits (scanned): ").append(totalCommits).append("");
            sb.append("Commits by author:").append(metrics.get("commits_by_author")).append("// ");
            scores.put("activity", Math.min(1.0, totalCommits / 100.0));
        } else {
            sb.append("No git metrics found.");
            scores.put("activity", 0.0);
        }

        if (php != null) {
            sb.append("PHPMetrics: report available. Exit code:").append(php.getInteger("exit_code", -1)).append("");
            String reportPath = php.getString("json_report");
            if (reportPath != null) {
                sb.append("PHPMetrics JSON: ").append(reportPath).append("");
            }
            scores.put("php_quality", php.getInteger("exit_code", 1) == 0 ? 0.7 : 0.2);
        } else {
            sb.append("No phpmetrics result found.");
            scores.put("php_quality", 0.0);
        }

        if (sonar != null) {
            sb.append("Sonar: exit code: ").append(sonar.getInteger("exit_code",
                    -1)).append("");
            sb.append("Sonar raw output length: ")
                    .append(sonar.getString("output") == null ? 0 : sonar.getString("output").length())
                    .append(" chars");
            scores.put("code_quality", sonar.getInteger("exit_code", 1) == 0 ? 0.6 : 0.3);
        } else {
            sb.append("No sonar data found.");
            scores.put("code_quality", 0.0);
        }
        double activity = ((Number) scores.getOrDefault("activity",
                0.0)).doubleValue();
        double phpq = ((Number) scores.getOrDefault("php_quality",
                0.0)).doubleValue();
        double codeq = ((Number) scores.getOrDefault("code_quality",
                0.0)).doubleValue();
        double overall = (activity * 0.3) + (phpq * 0.3) + (codeq * 0.4);
        scores.put("overall", overall);

        sb.append("Overall heuristic score: ").append(String.format("%.2f", overall)).append("");

        Document summary = new Document("summary_text", sb.toString())
                .append("scores", new Document(scores));
        return summary;
    }
}