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

            // registra o container no serviço
            AgentMessenger.setContainer(container);

            // inicia o HTTP REST
            MessageEndpoint.start();

            container.createNewAgent("CoordinatorAgent",
                    "br.uerj.multiagentes.agentes.CoordinatorAgent", null).start();

            container.createNewAgent("SonarAgent",
                    "br.uerj.multiagentes.agentes.SonarAgent", null).start();

            container.createNewAgent("PhpAgent",
                    "br.uerj.multiagentes.agentes.PhpAgent", null).start();

            container.createNewAgent("MetricsAgent",
                    "br.uerj.multiagentes.agentes.MetricsAgent", null).start();

            container.createNewAgent("LlmAgent",
                    "br.uerj.multiagentes.agentes.LlmAgent", null).start();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
