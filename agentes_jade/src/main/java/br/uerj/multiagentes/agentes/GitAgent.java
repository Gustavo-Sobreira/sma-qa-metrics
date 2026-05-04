package br.uerj.multiagentes.agentes;

import br.uerj.multiagentes.utils.AgentDirectory;
import br.uerj.multiagentes.utils.GsonProvider;
import br.uerj.multiagentes.utils.MongoHelper;

import com.google.gson.Gson;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

import org.bson.Document;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

public class GitAgent extends Agent {

    private static final Gson gson = GsonProvider.get();
    private static final Semaphore CONCURRENCY = new Semaphore(
            Integer.parseInt(System.getenv().getOrDefault("GIT_MAX_CONCURRENCY", "1")));

    @Override
    protected void setup() {
        AgentDirectory.register(this, "git", "git_agent");

        addBehaviour(new CyclicBehaviour() {
            public void action() {

                ACLMessage msg = receive();
                if (msg == null) {
                    block();
                    return;
                }

                if (!"RUN_GIT".equals(msg.getOntology()))
                    return;

                Map payload = gson.fromJson(msg.getContent(), Map.class);

                String run_id = String.valueOf(payload.get("run_id"));
                String repo_url = String.valueOf(payload.get("repo_url"));
                String repo_path = String.valueOf(payload.get("repo_path"));
                String git_ref = stringValue(payload.get("git_ref"), "");
                String sonar_project_key = String.valueOf(payload.get("sonar_project_key"));
                String sonar_project_name = String.valueOf(payload.get("sonar_project_name"));
                log(run_id, "RECEIVED", "RUN_GIT", payload);

                if (isRunFailed(run_id)) {
                    log(run_id, "IGNORED_AFTER_FAILED", "RUN_GIT", payload);
                    return;
                }

                String output;
                boolean ok;
                boolean acquired = false;

                try {
                    CONCURRENCY.acquire();
                    acquired = true;
                    log(run_id, "CONCURRENCY_ACQUIRED", "RUN_GIT", Map.of("available_permits", CONCURRENCY.availablePermits()));

                    CommandResult result;
                    if (new java.io.File(repo_path + "/.git").exists()) {
                        result = updateExistingRepository(repo_path, git_ref);
                    } else {
                        result = cloneRepository(repo_url, repo_path, git_ref);
                    }

                    output = result.output;
                    ok = result.exitCode == 0;

                } catch (Exception e) {
                    output = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    ok = false;
                } finally {
                    if (acquired) {
                        CONCURRENCY.release();
                        log(run_id, "CONCURRENCY_RELEASED", "RUN_GIT", Map.of("available_permits", CONCURRENCY.availablePermits()));
                    }
                }

                Map<String, Object> resp = Map.of(
                        "run_id", run_id,
                        "repo_url", repo_url,
                        "repo_path", repo_path,
                        "git_ref", git_ref,
                        "sonar_project_key", sonar_project_key,
                        "sonar_project_name", sonar_project_name,
                        "ok", ok,
                        "reason", output
                );

                send("coordinator", ok ? "GIT_DONE" : "GIT_FAILED", resp);
                log(run_id, "SENT", ok ? "GIT_DONE" : "GIT_FAILED", resp);
            }
        });
    }

    private void send(String to, String ontology, Object payload) {
        ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
        msg.setOntology(ontology);
        msg.setContent(gson.toJson(payload));

        List<AID> list = AgentDirectory.find(this, to);

        if (list != null && !list.isEmpty())
            list.forEach(msg::addReceiver);
        else
            msg.addReceiver(new AID("coordinator_agent", AID.ISLOCALNAME));

        send(msg);
    }

    private CommandResult cloneRepository(String repoUrl, String repoPath, String gitRef) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("clone");

        if (!gitRef.isBlank()) {
            cmd.add("--branch");
            cmd.add(gitRef);
            cmd.add("--single-branch");
        }

        cmd.add(repoUrl);
        cmd.add(repoPath);

        return run(new ProcessBuilder(cmd));
    }

    private CommandResult updateExistingRepository(String repoPath, String gitRef) {
        StringBuilder output = new StringBuilder();

        CommandResult fetch = run(new ProcessBuilder("git", "-C", repoPath, "fetch", "--all", "--tags", "--prune"));
        output.append(fetch.output);
        if (fetch.exitCode != 0) {
            return new CommandResult(fetch.exitCode, output.toString());
        }

        if (!gitRef.isBlank()) {
            CommandResult checkout = run(new ProcessBuilder("git", "-C", repoPath, "checkout", gitRef));
            output.append(checkout.output);
            if (checkout.exitCode != 0) {
                return new CommandResult(checkout.exitCode, output.toString());
            }

            CommandResult upstream = run(new ProcessBuilder(
                    "git", "-C", repoPath, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}"));
            if (upstream.exitCode == 0) {
                CommandResult pull = run(new ProcessBuilder("git", "-C", repoPath, "pull", "--ff-only"));
                output.append(pull.output);
                if (pull.exitCode != 0) {
                    return new CommandResult(pull.exitCode, output.toString());
                }
            }

            CommandResult status = run(new ProcessBuilder("git", "-C", repoPath, "status", "--short", "--branch"));
            output.append(status.output);
            return new CommandResult(status.exitCode, output.toString());
        }

        CommandResult pull = run(new ProcessBuilder("git", "-C", repoPath, "pull", "--ff-only"));
        output.append(pull.output);
        return new CommandResult(pull.exitCode, output.toString());
    }

    private CommandResult run(ProcessBuilder pb) {
        StringBuilder out = new StringBuilder();
        int exitCode = -1;

        try {
            pb.redirectErrorStream(true);
            Process p = pb.start();

            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;

            while ((line = r.readLine()) != null)
                out.append(line).append("\n");

            exitCode = p.waitFor();

        } catch (Exception e) {
            out.append(e.getMessage());
        }

        return new CommandResult(exitCode, out.toString());
    }

    private boolean isRunFailed(String run_id) {
        try {
            Document status = MongoHelper.getDatabase()
                    .getCollection("runStatus")
                    .find(new Document("run_id", run_id))
                    .first();

            return status != null &&
                    (Boolean.TRUE.equals(status.getBoolean("failed")) || "FAILED".equals(status.getString("stage")));
        } catch (Exception e) {
            return false;
        }
    }

    private void log(String run_id, String event, String ontology, Object data) {
        try {
            MongoHelper.getDatabase().getCollection("logs").insertOne(new Document()
                    .append("run_id", run_id)
                    .append("agent", "git")
                    .append("event", event)
                    .append("ontology", ontology)
                    .append("data", data == null ? new Document() : Document.parse(gson.toJson(data)))
                    .append("created_at", Instant.now().toString()));
        } catch (Exception ignored) {}
    }

    private String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String s = String.valueOf(value);
        if (s.isBlank() || "null".equalsIgnoreCase(s)) return fallback;
        return s;
    }

    private static class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }
    }
}
