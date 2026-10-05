package org.fuin.sokar.daemon;

import static org.fuin.sokar.daemon.SokarDaemon.*;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.app.EgressControl;
import org.fuin.sokar.app.RunningEgress;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.clearance.ClearanceService;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for egress, the shield and clearance.
 * <p>
 * One class per area, so a change to one area's methods is a change to its own file; {@link SokarDaemon} only
 * says which areas there are.
 * <p>
 * Every running task already serves its own prompts on its own socket - the watcher's
 * 'org.fuin.sokar.Clearance1'. This is the one socket an interface talks to instead of
 * discovering and connecting to each of them, and it is where lag actually costs
 * something: a prompt expires while a client polls, and the task stays blocked.
 */
final class EgressMethods {

    private EgressMethods() {
    }

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     */
    @SuppressWarnings("unused")
    static void register(VarlinkServer server, SokarContext context, TaskInventory inventory, TaskControl control) {
        server.method("Sets", (parameters, replies) -> {
            // What a chooser offers. Scanned rather than listed anywhere: a set an operator added
            // is a file they dropped in a directory, and it has to appear without anything being
            // rebuilt.
            final var directory = context.paths().egress().egressSets();
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("sets", directory.all().values().stream()
                    .map(set -> Map.<String, Object>of("name", set.name(), "label", set.label(),
                            "domains", set.domains()))
                    .toList());
            answer.put("locations", directory.locations().stream().map(Path::toString).toList());
            replies.last(answer);
        });

        server.method("Egress", (parameters, replies) -> {
            final Path file = projectFile(parameters, context);
            final EgressControl egress = new EgressControl(context);
            // Which repository's view of it. A repository's grants are ADDED to the project's, so
            // asking without one answers the project-level set - true of every repository - and
            // asking with one answers that plus what it adds.
            final String repository = text(parameters, "repository");
            // Absent, not empty: a ?string that was not sent arrives as "" here, and an empty
            // agent name is not a request for the default - it is a request for an agent called
            // nothing, which is refused. Measured over the wire on the first call.
            final String agent = text(parameters, "agent");
            final String named = agent.isEmpty() ? null : agent;
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("hosts", hosts(egress.reachable(file,
                    repository.isEmpty() ? null : repository, named)));
            // The names only, as the contract has always carried them; now the project's too.
            answer.put("refused", List.copyOf(egress.refusals(file, repository.isEmpty() ? null : repository,
                    named).keySet()));
            replies.last(answer);
        });

        server.method("SetEgress", (parameters, replies) -> {
            final EgressControl.Effect effect = new EgressControl(context).apply(
                    projectFile(parameters, context),
                    // Which block it lands in. A repository's grants are added to the project's,
                    // so this never takes anything away from the repository it names.
                    empty(parameters, "repository"),
                    new EgressControl.Change(strings(parameters, "addSets"),
                            strings(parameters, "removeSets"),
                            strings(parameters, "addDomains"),
                            strings(parameters, "removeDomains")),
                    flag(parameters, "dryRun"));
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("outcome", effect.outcome().name());
            answer.put("opens", hosts(effect.opens()));
            answer.put("closes", hosts(effect.closes()));
            answer.put("cost", effect.cost() == null ? "" : effect.cost());
            answer.put("detail", effect.detail() == null ? "" : effect.detail());
            replies.last(answer);
        });

        server.method("NarrowTask", (parameters, replies) -> {
            // The scope is required here for the same reason it is for widening, and the mistake
            // it prevents is worse in this direction: narrowing only the run when somebody meant
            // the project too leaves the next task starting with the host still open.
            final String scope = text(parameters, "scope");
            if (scope.isEmpty()) {
                throw new VarlinkException(INTERFACE + ".ScopeRequired", Map.of());
            }
            final RunningEgress.Withdrawal taken = new RunningEgress(context).narrow(
                    text(parameters, "task"), strings(parameters, "domains"),
                    "RUN_AND_PROJECT".equalsIgnoreCase(scope)
                            ? RunningEgress.Scope.RUN_AND_PROJECT : RunningEgress.Scope.RUN,
                    flag(parameters, "dryRun"));
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("outcome", taken.outcome().name());
            answer.put("closes", taken.closes());
            answer.put("addresses", taken.addresses());
            answer.put("persisted", taken.persisted());
            answer.put("detail", taken.detail() == null ? "" : taken.detail());
            replies.last(answer);
        });

        server.method("WidenTask", (parameters, replies) -> {
            // The scope is read without a default on purpose: "this run only" and "this run and
            // the project" are different intentions, and a client that did not say which one it
            // meant must not have one chosen for it.
            final String scope = text(parameters, "scope");
            if (scope.isEmpty()) {
                throw new VarlinkException(INTERFACE + ".ScopeRequired", Map.of());
            }
            final RunningEgress.Effect effect = new RunningEgress(context).widen(
                    text(parameters, "task"), strings(parameters, "domains"),
                    "RUN_AND_PROJECT".equalsIgnoreCase(scope)
                            ? RunningEgress.Scope.RUN_AND_PROJECT : RunningEgress.Scope.RUN,
                    flag(parameters, "dryRun"));
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("outcome", effect.outcome().name());
            answer.put("opens", effect.opens());
            answer.put("persisted", effect.persisted());
            answer.put("detail", effect.detail() == null ? "" : effect.detail());
            replies.last(answer);
        });

        server.method("SetClearance", (parameters, replies) -> {
            final java.io.StringWriter said = new java.io.StringWriter();
            final org.fuin.sokar.app.RunningClearance.Result result =
                    org.fuin.sokar.app.RunningClearance.set(context, text(parameters, "task"),
                            text(parameters, "mode"), flag(parameters, "dryRun"),
                            new PrintWriter(said, true));
            replies.last(Map.of("outcome", result.outcome().name(), "was", result.was(),
                    "now", result.now(), "detail", result.detail()));
        });

        server.method("Prompts", (parameters, replies) -> {
            if (!replies.streaming()) {
                // Answering a stream once would look like it worked and then deliver nothing
                // ever again. The watcher's own Subscribe refuses the same way.
                throw new VarlinkException(INTERFACE + ".StreamRequired",
                        Map.of("method", "Prompts"));
            }
            final org.fuin.sokar.clearance.Backlog events = new org.fuin.sokar.clearance.Backlog(1024);
            final Map<String, Thread> watching = new java.util.concurrent.ConcurrentHashMap<>();
            try {
                while (replies.open()) {
                    subscribe(context, inventory, events, watching);
                    final Map<String, Object> event =
                            events.poll(PROMPT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
                    if (event != null) {
                        replies.more(event);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                // The client left, so nothing needs these any more. Each subscription is a
                // connection to another process; leaving them open would accumulate one per
                // client that ever watched.
                watching.values().forEach(Thread::interrupt);
            }
        });

        server.method("Decide", (parameters, replies) -> {
            final String task = text(parameters, "task");
            final Path clearance = clearanceSocket(context, task);
            if (clearance == null) {
                // A task that is not running has no watcher, so there is nobody to tell. Said
                // plainly rather than accepted and dropped: an answer that goes nowhere leaves
                // the operator believing they unblocked something.
                throw new VarlinkException(INTERFACE + ".NoClearance", Map.of("task", task));
            }
            try (VarlinkClient client = new VarlinkClient(clearance)) {
                final Map<String, Object> answer = client.call(
                        ClearanceService.INTERFACE + ".Verdict",
                        Map.of("key", text(parameters, "key"),
                                "address", text(parameters, "address"),
                                "allow", flag(parameters, "allow")));
                replies.last(Map.of("ok", Boolean.TRUE.equals(answer.get("ok"))));
            }
        });
    }
}
