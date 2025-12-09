package br.uerj.multiagentes;

import jade.core.Runtime;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;

import br.uerj.multiagentes.utils.AgentMessenger;

public class MainContainer {

    public static AgentContainer container;

    public static void main(String[] args) {
        try {

            Runtime rt = Runtime.instance();
            Profile p = new ProfileImpl();

            container = rt.createMainContainer(p);

            AgentMessenger.setContainer(container);
            MessageEndpoint.start();

            container.createNewAgent("coordinator_agent",
                    "br.uerj.multiagentes.agentes.CoordinatorAgent", null).start();

            container.createNewAgent("qa_agent",
                    "br.uerj.multiagentes.agentes.QaAgent", null).start();

            container.createNewAgent("sonar_agent",
                    "br.uerj.multiagentes.agentes.SonarAgent", null).start();

            container.createNewAgent("php_agent",
                    "br.uerj.multiagentes.agentes.PhpAgent", null).start();

            container.createNewAgent("metrics_agent",
                    "br.uerj.multiagentes.agentes.MetricsAgent", null).start();

            container.createNewAgent("llm_agent",
                    "br.uerj.multiagentes.agentes.LlmAgent", null).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
