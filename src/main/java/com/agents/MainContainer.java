package com.agents;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentController;
import jade.wrapper.ContainerController;
import jade.core.AID;
import jade.lang.acl.ACLMessage;
import static spark.Spark.*;

public class MainContainer {
    public static void main(String[] args) {
        try {
            Runtime rt = Runtime.instance();
            Profile profile = new ProfileImpl();
            profile.setParameter(Profile.GUI, "false"); 
            profile.setParameter(Profile.MAIN_HOST, "localhost");

            ContainerController container = rt.createMainContainer(profile);

            AgentController coordinator = container.createNewAgent(
                "coordinator",
                "com.agents.CoordinatorAgent",
                new Object[]{}
            );

            AgentController qa = container.createNewAgent(
                "qaAgent",
                "com.agents.QaAgent",
                new Object[]{}
            );

            AgentController metrics = container.createNewAgent(
                "metricsAgent",
                "com.agents.MetricsAgent",
                new Object[]{}
            );

            coordinator.start();
            qa.start();
            metrics.start();

            port(8080);
            post("/webhook", (req, res) -> {
                String body = req.body();
                ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
                msg.addReceiver(new AID("coordinator", AID.ISLOCALNAME));
                msg.setLanguage("JSON");
                msg.setContent(body);

                CoordinatorAgent.enqueueMessage(msg);

                res.status(200);
                return "ok";
            });

            System.out.println("MainContainer started successfully!");

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to start JADE container.");
        }
    }
}
