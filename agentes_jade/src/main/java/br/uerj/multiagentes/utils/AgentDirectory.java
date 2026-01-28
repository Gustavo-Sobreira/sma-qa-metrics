package br.uerj.multiagentes.utils;

import jade.core.Agent;
import jade.core.AID;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.ArrayList;
import java.util.List;

public final class AgentDirectory {

    private AgentDirectory() {}

    public static void register(Agent agent, String serviceType, String serviceName) {
        try {
            DFAgentDescription dfd = new DFAgentDescription();
            dfd.setName(agent.getAID());

            ServiceDescription sd = new ServiceDescription();
            sd.setType(serviceType);
            sd.setName(serviceName);

            dfd.addServices(sd);
            DFService.register(agent, dfd);
        } catch (FIPAException e) {
            System.out.println("[DF] Falha ao registrar serviço " + serviceType + ": " + e.getMessage());
        }
    }

    public static List<AID> find(Agent agent, String serviceType) {
        List<AID> out = new ArrayList<>();
        try {
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(serviceType);
            template.addServices(sd);

            DFAgentDescription[] result = DFService.search(agent, template);
            for (DFAgentDescription d : result) {
                out.add(d.getName());
            }
        } catch (FIPAException e) {
            System.out.println("[DF] Falha ao buscar serviço " + serviceType + ": " + e.getMessage());
        }
        return out;
    }
}
