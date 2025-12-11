package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import br.uerj.multiagentes.utils.MongoHelper;

import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;

import org.bson.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Não remova meus comentários.
 */
public class CodeAnalyzerAgent extends Agent {

    private final Gson gson = new Gson();
    private final Map<String, Set<String>> pending = new HashMap<>();

    @Override
    protected void setup() {
        System.out.println("[ 🧪 - CodeAnalyzerAgent ]\n     |->  Pronto.");

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {

                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }

                try {

                    String ontology = msg.getOntology();

                    if (msg.getPerformative() == ACLMessage.INFORM &&
                            "Repo-Cloned".equals(ontology)) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = (String) payload.get("run_id");
                        String repoPath = (String) payload.get("repo_path");

                        pending.put(runId, new HashSet<>());

                        Map<String, Object> sonarBody = new HashMap<>();
                        sonarBody.put("run_id", runId);
                        sonarBody.put("repo_path", repoPath);

                        ACLMessage sonarReq = new ACLMessage(ACLMessage.REQUEST);
                        sonarReq.addReceiver(new AID("sonar_agent", AID.ISLOCALNAME));
                        sonarReq.setOntology("RUN_SONAR");
                        sonarReq.setContent(gson.toJson(sonarBody));
                        send(sonarReq);

                        // Sá bosta parou de funcionar na versão 8 e o animal aqui não testou
                        // Map<String, Object> phpBody = new HashMap<>();
                        // phpBody.put("run_id", runId);
                        // phpBody.put("repo_path", repoPath);

                        // ACLMessage phpReq = new ACLMessage(ACLMessage.REQUEST);
                        // phpReq.addReceiver(new AID("php_agent", AID.ISLOCALNAME));
                        // phpReq.setOntology("RUN_PHPMETRICS");
                        // phpReq.setContent(gson.toJson(phpBody));
                        // send(phpReq);

                        return;
                    }

                    if (msg.getPerformative() == ACLMessage.INFORM &&
                            "QA_SUBTASK_DONE".equals(ontology)) {

                        Map payload = gson.fromJson(msg.getContent(), Map.class);
                        String runId = (String) payload.get("run_id");
                        String agent = (String) payload.get("agent");

                        if (payload.get("error") != null) {
                            System.out.println("[ 🧪 - CodeAnalyzerAgent ]\n     |->  Agent falhou: " + agent +
                                    " error=" + payload.get("error"));
                        }

                        Set<String> done = pending.computeIfAbsent(runId, k -> new HashSet<>());
                        done.add(agent);

                        // Descomentar quando o outro agente tiver funcionando
                        // if (done.size() >= 2) {
                        if (done.size() >= 1) {

                            String projectKey = System.getenv().getOrDefault("SONAR_PROJECT", "sma-project");
                            String baseUrl = System.getenv().getOrDefault("SONAR_URL", "http://sonarqube:9000");

                            String sonarJson = "{}";
                            try {
                                String metricsList = getAllMetrics(baseUrl);
                                if (metricsList == null || metricsList.isBlank()) {
                                    System.out.println(
                                            "[ 🧪 - CodeAnalyzerAgent ]\n     |->  Nenhuma métrica encontrada no Sonar.");
                                } else {
                                    sonarJson = fetchAllMetrics(baseUrl, projectKey, metricsList);
                                }
                            } catch (Exception ex) {
                                System.out
                                        .println(
                                                "[ 🧪 - CodeAnalyzerAgent ]\n     |->  Erro ao buscar métricas do Sonar: "
                                                        + ex.getMessage());
                                ex.printStackTrace();
                            }

                            MongoDatabase db = MongoHelper.getDatabase();
                            MongoCollection<Document> coll = db.getCollection("sonar_metrics");

                            Document doc = new Document();
                            doc.put("run_id", runId);
                            doc.put("project_key", projectKey);
                            try {
                                doc.put("metrics", Document.parse(sonarJson));
                            } catch (Exception e) {
                                doc.put("metrics_raw", sonarJson);
                            }
                            doc.put("stored_at", Instant.now().toString());

                            coll.insertOne(doc);

                            Document stored = coll.find(new Document("run_id", runId)).first();
                            String fullJson = stored != null ? stored.toJson() : "{}";

                            MongoCollection<Document> runs = db.getCollection("run_status");
                            runs.updateOne(
                                    new Document("run_id", runId),
                                    new Document("$set", new Document("qa_completed_at",
                                            Instant.now().toString())));

                            Map<String, Object> notifyBody = new HashMap<>();
                            notifyBody.put("type", "QA_COMPLETED");
                            notifyBody.put("run_id", runId);
                            try {
                                notifyBody.put("sonar_metrics", gson.fromJson(sonarJson, Object.class));
                            } catch (Exception e) {
                                notifyBody.put("sonar_metrics_raw", sonarJson);
                            }

                            ACLMessage notify = new ACLMessage(ACLMessage.INFORM);
                            notify.addReceiver(new AID("llm_agent", AID.ISLOCALNAME));
                            notify.setOntology("QA_COMPLETED");
                            notify.setContent(gson.toJson(notifyBody));
                            send(notify);

                            pending.remove(runId);
                        }

                        return;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private String getAllMetrics(String baseUrl) throws Exception {
        String url = baseUrl + "/api/metrics/search?ps=500";

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        String token = System.getenv().getOrDefault("SONAR_TOKEN", "");
        if (!token.isBlank()) {
            String auth = token + ":";
            String basic = java.util.Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }

        HttpRequest request = builder.build();

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            throw new RuntimeException(
                    "Sonar metrics API returned status " + resp.statusCode() + " body=" + resp.body());
        }

        Map map = gson.fromJson(resp.body(), Map.class);
        var metrics = (java.util.List<Map>) map.get("metrics");
        if (metrics == null || metrics.isEmpty())
            return "";

        StringBuilder sb = new StringBuilder();
        for (Map m : metrics) {
            if (m.get("key") != null) {
                sb.append((String) m.get("key")).append(",");
            }
        }

        if (sb.length() == 0)
            return "";
        return sb.substring(0, sb.length() - 1);
    }

    private String fetchAllMetrics(String baseUrl, String project, String metrics) throws Exception {
        String comp = URLEncoder.encode(project, StandardCharsets.UTF_8.toString());
        String metricKeys = URLEncoder.encode(metrics, StandardCharsets.UTF_8.toString());

        String url = baseUrl + "/api/measures/component?component=" + comp + "&metricKeys=" + metricKeys;

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET();

        String token = System.getenv().getOrDefault("SONAR_TOKEN", "");
        if (!token.isBlank()) {
            String auth = token + ":";
            String basic = java.util.Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            builder.header("Authorization", "Basic " + basic);
        }

        HttpRequest request = builder.build();

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            System.out.println(
                    "[ 🧪 - CodeAnalyzerAgent ]\n     |->  Sonar API  status " + resp.statusCode());
            return "{}";
        }

        return resp.body();
    }
}
