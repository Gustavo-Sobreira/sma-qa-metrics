package br.uerj.multiagentes.agentes;

import com.google.gson.Gson;
import jade.core.Agent;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class LlmAgent extends Agent {

    private final Gson gson = new Gson();

    private final Map<String, Boolean> qaDone = new ConcurrentHashMap<>();
    private final Map<String, Boolean> gitDone = new ConcurrentHashMap<>();

    @Override
    protected void setup() {
        System.out.println("[ 🤖 - LlmAgent ]\n     |->  Pronto.");

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
                    String ontology = msg.getOntology();
                    String content = msg.getContent();

                    Map payload = gson.fromJson(content, Map.class);
                    String runId = (String) payload.get("run_id");

                    if (runId == null) {
                        System.out.println("[ 🤖 - LlmAgent ] Mensagem ignorada: payload sem run_id");
                        return;
                    }

                    if ("GIT_METRICS_DONE".equals(ontology)) {
                        gitDone.put(runId, true);
                        System.out.println("[ 🤖 - LlmAgent ]\n     |->  Dados recebidos de GitLogAgent.");
                    }

                    if ("QA_COMPLETED".equals(ontology)) {
                        qaDone.put(runId, true);
                        System.out.println("[ 🤖 - LlmAgent ]\n     |->  Dados recebidos de CodeAnalyzerAgent.");
                    }

                    if (qaDone.getOrDefault(runId, false) &&
                            gitDone.getOrDefault(runId, false)) {

                        System.out.println(
                                "[ 🤖 - LlmAgent ]\n     |->  Dados todos dados recebidos, iniciando análise. Aguarde . . .");

                        startAnalysis(runId);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void startAnalysis(String runId) {
        try {

            System.out.println("[ 🤖 - LlmAgent ] Lendo métricas no MongoDB...");

            Map<String, Object> allMetrics = Map.of(
                    "run_id", runId,
                    "qa_metrics", "aqui vão métricas reais do QA",
                    "git_metrics", "aqui vão as métricas de LOC e NALOC");

            String prompt = "Analise as seguintes métricas de qualidade e git:\n\n"
                    + gson.toJson(allMetrics)
                    + "\n\nForneça um resumo técnico objetivo.";

            String llmResponse = callOllama(prompt);

            System.out.println("\n\n==============================================");
            System.out.println("📊  RESULTADO DA ANÁLISE LLM (run_id = " + runId + ")");
            System.out.println("==============================================\n");
            System.out.println(llmResponse);
            System.out.println("==============================================\n");

            ACLMessage out = new ACLMessage(ACLMessage.INFORM);
            out.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));
            out.setOntology("LLM_ANALYSIS_COMPLETE");
            out.setContent(gson.toJson(Map.of(
                    "run_id", runId,
                    "result", llmResponse)));

            send(out);

            System.out.println("[ 🤖 - LlmAgent ]\n     |->  Análise enviada ao CoordinatorAgent.");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private String callOllama(String prompt) throws Exception {

        URL url = new URL("http://ollama:11434/api/generate");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();

        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json");
        con.setDoOutput(true);

        String model = "llama3";

        String body = gson.toJson(Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false));

        OutputStreamWriter writer = new OutputStreamWriter(con.getOutputStream());
        writer.write(body);
        writer.flush();
        writer.close();

        BufferedReader reader = new BufferedReader(new InputStreamReader(con.getInputStream()));

        StringBuilder response = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            response.append(line);
        }

        reader.close();

        Map resp = gson.fromJson(response.toString(), Map.class);

        return (String) resp.getOrDefault("response", "");
    }
}
