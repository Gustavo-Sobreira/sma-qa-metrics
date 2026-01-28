package br.uerj.multiagentes;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;

/**
 * Bootstrap do contêiner principal JADE.
 *
 * Observação: os nomes locais (local-name) dos agentes são usados como endereços
 * na troca de mensagens (AID.ISLOCALNAME).
 */
public class MainContainer {

    public static void main(String[] args) {
        try {
            Runtime rt = Runtime.instance();
            Profile profile = new ProfileImpl();

            AgentContainer container = rt.createMainContainer(profile);

            container.createNewAgent("coordinator_agent",
                    "br.uerj.multiagentes.agentes.CoordinatorAgent",
                    null).start();

            container.createNewAgent("code_analyzer_agent",
                    "br.uerj.multiagentes.agentes.CodeAnalyzerAgent",
                    null).start();

            container.createNewAgent("sonar_agent",
                    "br.uerj.multiagentes.agentes.SonarAgent",
                    null).start();

            container.createNewAgent("git_log_agent",
                    "br.uerj.multiagentes.agentes.GitLogAgent",
                    null).start();

            container.createNewAgent("llm_agent",
                    "br.uerj.multiagentes.agentes.LlmAgent",
                    null).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
