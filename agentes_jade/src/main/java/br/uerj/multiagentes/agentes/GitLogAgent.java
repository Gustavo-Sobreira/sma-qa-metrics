    package br.uerj.multiagentes.agentes;

    import com.google.gson.Gson;
    import jade.core.Agent;
    import jade.lang.acl.ACLMessage;
    import jade.lang.acl.MessageTemplate;
    import jade.core.AID;

    import org.bson.Document;

    import java.io.BufferedReader;
    import java.io.InputStreamReader;
    import java.time.Instant;
    import java.util.*;

    import com.mongodb.client.MongoDatabase;
    import com.mongodb.client.MongoCollection;

    import br.uerj.multiagentes.utils.MongoHelper;

    public class GitLogAgent extends Agent {

        private final Gson gson = new Gson();
        @Override
        protected void setup() {
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

                    try {
                        Map payload = gson.fromJson(msg.getContent(), Map.class);

                        if (payload == null) {
                            System.out.println("[ 📟 - GitLogAgent ] Mensagem recebida vazia/inválida.");
                            return;
                        }

                        if ("NEW_REPO".equals(payload.get("type"))
                                || (payload.get("run_id") != null && payload.get("repo_path") != null)) {

                            String runId = String.valueOf(payload.get("run_id"));
                            String repoPath = String.valueOf(payload.get("repo_path"));

                            System.out.println(
                                    "[ 📟 - GitLogAgent - ⌛]\n     |->  Processando métricas LOC/NALOC, aguarde...");

                            Map<String, Object> locMetrics = calcularLOCporCommit(repoPath);

                            boolean inserted = false;

                            try {
                                MongoDatabase db = MongoHelper.getDatabase();
                                MongoCollection<Document> col = db.getCollection("git_metrics");

                                Document metrics = new Document()
                                        .append("run_id", runId)
                                        .append("repo_path", repoPath)
                                        .append("loc_metrics", locMetrics)
                                        .append("generated_at", Instant.now().toString());

                                col.insertOne(metrics);
                                inserted = true;

                                System.out.println("[ 📟 - GitLogAgent ] Métricas LOC/NALOC inseridas no Mongo (run=" + runId + ")");
                            } catch (Exception e) {
                                System.out.println("[ 📟 - GitLogAgent ] Erro ao inserir métricas no Mongo: " + e.getMessage());
                                e.printStackTrace();
                            }

                            try {
                                MongoDatabase db = MongoHelper.getDatabase();
                                MongoCollection<Document> runs = db.getCollection("run_status");

                                Document update = new Document("$set",
                                        new Document("git_metrics_done", inserted)
                                                .append("git_metrics_finished_at", Instant.now().toString()));

                                runs.updateOne(new Document("run_id", runId), update);

                            } catch (Exception e) {
                                System.out.println("[ 📟 - GitLogAgent ] Erro ao atualizar run_status: " + e.getMessage());
                                e.printStackTrace();
                            }

                            try {
                                ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                                notify.addReceiver(new AID("llm_agent", AID.ISLOCALNAME));
                                notify.setOntology("GIT_METRICS_DONE");
                                notify.setContent(gson.toJson(Map.of(
                                        "run_id", runId,
                                        "ok", inserted
                                )));
                                send(notify);

                                System.out.println("[ 📟 - GitLogAgent ] Notify enviado para LlmAgent (run=" + runId + " ok=" + inserted + ")");
                            } catch (Exception e) {
                                System.out.println("[ 📟 - GitLogAgent ] Erro ao notificar LlmAgent: " + e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    } catch (Exception e) {
                        System.out.println("[ 📟 - GitLogAgent ] Exceção no processamento: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            });
        }

        private Map<String, Object> calcularLOCporCommit(String repoDir) {

            Map<String, Object> result = new HashMap<>();
            List<Map<String, Object>> commits = new ArrayList<>();

            try {
                Process process = new ProcessBuilder(
                        "bash", "-c",
                        "git -C " + repoDir + " log --numstat --pretty=format:'--commit--%H'"
                ).start();

                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

                String line;
                String currentCommit = null;
                int locSum = 0;
                int nalocSum = 0;

                while ((line = reader.readLine()) != null) {

                    if (line.startsWith("--commit--")) {
                        if (currentCommit != null) {
                            commits.add(Map.of(
                                    "commit", currentCommit,
                                    "LOC", locSum,
                                    "NALOC", nalocSum
                            ));
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
                    commits.add(Map.of(
                            "commit", currentCommit,
                            "LOC", locSum,
                            "NALOC", nalocSum
                    ));
                }

                result.put("commits", commits);

            } catch (Exception e) {
                System.out.println("[ 📟 - GitLogAgent ] Erro ao calcular LOC/NALOC: " + e.getMessage());
            }

            return result;
        }
    }
        