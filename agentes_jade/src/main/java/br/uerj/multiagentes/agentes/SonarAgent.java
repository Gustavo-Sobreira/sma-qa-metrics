package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;

import com.google.gson.Gson;
import jade.core.AID;
import jade.core.Agent;
import jade.lang.acl.ACLMessage;

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SonarAgent extends Agent {

    private final Gson gson = GsonProvider.get();
    private final HttpClient http = HttpClient.newHttpClient();

    private static final String WORKER_URL = System.getenv().getOrDefault(
            "SONAR_WORKER_URL",
            "http://sonar-worker:9100/scan"
    );

    private static final Pattern EXIT_CODE_PATTERN = Pattern.compile("\"exit_code\"\\s*:\\s*(-?\\d+)");

    @Override
    protected void setup() {
        AgentDirectory.register(this, "sonar", "sonar");

        System.out.println("[ SonarAgent ] - Pronto. Worker=" + WORKER_URL);

        addBehaviour(new jade.core.behaviours.CyclicBehaviour() {
            @Override
            public void action() {
                ACLMessage msg = myAgent.receive();
                if (msg == null) {
                    block();
                    return;
                }

                if (msg.getPerformative() != ACLMessage.REQUEST || !"RUN_SONAR".equals(msg.getOntology())) {
                    return;
                }

                Map payload = gson.fromJson(msg.getContent(), Map.class);
                String runId = String.valueOf(payload.get("run_id"));
                String repoPath = String.valueOf(payload.get("repo_path"));

                try {
                    System.out.println("[ SonarAgent ] - Processando SonarQube para " + repoPath);

                    String reqBody = gson.toJson(Map.of(
                            "repo_path", repoPath,
                            "run_id", runId
                    ));

                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(WORKER_URL))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                            .build();

                    HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
                    String body = resp.body() == null ? "" : resp.body();

                    saveArtifact(repoPath, body);

                    boolean httpOk = (resp.statusCode() >= 200 && resp.statusCode() < 300);

                    Map worker;
                    try {
                        worker = gson.fromJson(body, Map.class);
                    } catch (Exception parseEx) {
                        String preview = preview(body);
                        failToCodeAnalyzer(runId, "sonar",
                                "worker response is not JSON (http=" + resp.statusCode() + ") preview=" + preview);
                        System.out.println("[ SonarAgent ] - FALHOU. Resposta não-JSON do worker.");
                        return;
                    }

                    boolean ok = false;
                    Object okObj = worker.get("ok");
                    ok = Boolean.TRUE.equals(okObj) || "true".equalsIgnoreCase(String.valueOf(okObj));

                    Integer exitCode = extractExitCode(worker, body);

                    if (ok && exitCode == null) {
                        String preview = preview(body);
                        failToCodeAnalyzer(runId, "sonar",
                                "worker ok=true but could not parse exit_code (http=" + resp.statusCode() + ") preview=" + preview);
                        System.out.println("[ SonarAgent ] - FALHOU. ok=true mas exit_code não pôde ser interpretado.");
                        return;
                    }

                    int exit = exitCode == null ? -999 : exitCode;

                    if (!(httpOk && ok && exit == 0)) {
                        String reason = "worker failed (http=" + resp.statusCode() + ", ok=" + ok + ", exit_code=" + exit + ")";
                        failToCodeAnalyzer(runId, "sonar", reason);
                        System.out.println("[ SonarAgent ] - FALHOU. " + reason + " (run=" + runId + ")");
                        return;
                    }

                    ACLMessage doneMsg = new ACLMessage(ACLMessage.INFORM);
                    doneMsg.addReceiver(new AID("code_analyzer_agent", AID.ISLOCALNAME));
                    doneMsg.setOntology("QA_SUBTASK_DONE");
                    doneMsg.setContent(gson.toJson(Map.of(
                            "run_id", runId,
                            "agent", "sonar",
                            "ok", true,
                            "status", resp.statusCode(),
                            "exit_code", exit
                    )));
                    send(doneMsg);

                    System.out.println("[ SonarAgent ] - Concluído. ok=true exit_code=0 (run=" + runId + ")");

                } catch (Exception e) {
                    failToCodeAnalyzer(runId, "sonar", e.getMessage());
                    e.printStackTrace();
                }
            }
        });
    }

    private Integer extractExitCode(Map worker, String body) {
        Object v = worker.get("exit_code");
        Integer parsed = parseIntLoose(v);
        if (parsed != null) return parsed;

        v = worker.get("exitCode");
        parsed = parseIntLoose(v);
        if (parsed != null) return parsed;

        Matcher m = EXIT_CODE_PATTERN.matcher(body == null ? "" : body);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (Exception ignored) {}
        }
        return null;
    }

    private Integer parseIntLoose(Object v) {
        if (v == null) return null;
        try {
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return null;
        }
    }

    private String preview(String body) {
        if (body == null) return "";
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private void failToCodeAnalyzer(String runId, String agent, String reason) {
        ACLMessage failMsg = new ACLMessage(ACLMessage.INFORM);
        failMsg.addReceiver(new AID("code_analyzer_agent", AID.ISLOCALNAME));
        failMsg.setOntology("QA_SUBTASK_FAILED");
        failMsg.setContent(gson.toJson(Map.of(
                "run_id", runId,
                "agent", agent,
                "reason", reason
        )));
        send(failMsg);
    }

    private void saveArtifact(String repoPath, String body) {
        try {
            File dir = new File(repoPath, ".sma/sonar");
            dir.mkdirs();

            File out = new File(dir, "scan-" + Instant.now().toEpochMilli() + ".json");
            try (FileWriter fw = new FileWriter(out)) {
                fw.write(body == null ? "" : body);
            }
        } catch (Exception ignored) {}
    }
}
