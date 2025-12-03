package br.uerj.multiagentes.utils;

import jade.lang.acl.ACLMessage;
import jade.core.AID;
import jade.wrapper.AgentContainer;
import jade.wrapper.ControllerException;

public class AgentMessenger {

    private static AgentContainer container;

    public static void setContainer(AgentContainer c) {
        container = c;
    }

    public static void send(String to, String content) {
        try {
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.addReceiver(new AID(to, AID.ISLOCALNAME));
            msg.setContent(content);

            container.getAgent("CoordinatorAgent").putO2AObject(msg, false);

        } catch (ControllerException e) {
            e.printStackTrace();
        }
    }
}
