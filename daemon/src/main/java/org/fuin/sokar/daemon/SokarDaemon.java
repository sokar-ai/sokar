package org.fuin.sokar.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.GateSupport;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskLaunch;
import org.fuin.sokar.app.EgressControl;
import org.fuin.sokar.app.ProjectInventory;
import org.fuin.sokar.app.RunningEgress;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.clearance.ClearanceService;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.runtime.ContainerName;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * Entry point of the {@code sokard} binary: the domain over a unix socket, for an interface that
 * never shells out to the CLI.
 * <p>
 * <strong>A socket, never a port.</strong> The socket is the only entry point, which makes remote
 * access a tunnelling problem rather than an authentication problem - there is no listener on any
 * interface to authenticate against in the first place. The filesystem decides who may connect:
 * the socket is owner-only, so another account on the same machine is refused by the kernel rather
 * than by a check this had to remember to write.
 * <p>
 * <strong>Varlink, because the agent contract already speaks it.</strong> Sokar drives every agent
 * over varlink, so the protocol, its client, its server and its streaming replies are already
 * built and already tested here. Introducing a second protocol for the same kind of local IPC
 * would be two things to get right instead of one.
 * <p>
 * <strong>The daemon is not a task's parent.</strong> It starts nothing and owns nothing: task
 * containers belong to the container runtime and their helpers are their own processes, recorded
 * in each task's state directory. Killing this leaves every running task exactly where it was,
 * which is what makes restarting it safe.
 */
public final class SokarDaemon {

    /**
     * Varlink interface this serves.
     * <p>
     * The trailing digit is the compatibility promise and is not decoration - it is varlink's own
     * convention, and {@code org.fuin.sokar.Clearance1} already follows it. Within one number the
     * interface only ever grows: methods, optional parameters and reply fields may be added, and
     * nothing that exists may be removed, renamed, retyped or made to mean something else. A
     * change that cannot be made that way gets the next number and is served <em>beside</em> this
     * one, so a frontend built against either keeps working while a fleet is upgraded piecemeal.
     * <p>
     * {@code GetInfo} lists what a daemon offers, so a client picks the newest name it
     * understands rather than comparing version numbers. The rest is per-method: calling
     * something an older daemon does not have answers {@code MethodNotFound}, which lets an
     * interface disable one feature instead of refusing to connect.
     */
    public static final String INTERFACE = "org.fuin.sokar.Tasks1";

    /** Name of the socket inside Sokar's runtime directory. */
    public static final String SOCKET = "sokard.sock";

    /** How often a watch looks for a change. */
    static final java.time.Duration WATCH_INTERVAL = java.time.Duration.ofMillis(500);

    /** How long a prompt stream waits before looking for newly started tasks. */
    static final java.time.Duration PROMPT_INTERVAL = java.time.Duration.ofMillis(500);

    /** How often a tail looks for new lines. */
    static final java.time.Duration TAIL_INTERVAL = java.time.Duration.ofMillis(200);

    /** Most bytes one tail reply carries, so a huge log cannot become one huge message. */
    private static final long MAX_CHUNK = 64 * 1024;

    private SokarDaemon() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns a server offering the calls, bound to the given socket.
     * <p>
     * Separate from {@link #main(String[])} so a test can drive the real thing over a real socket
     * rather than a stand-in: what matters about this is the wire, and a mock of the wire proves
     * nothing about it.
     *
     * @param context Where podman and the paths come from.
     * @param socket Where to bind.
     * @return The server, not yet running.
     */
    public static VarlinkServer serving(SokarContext context, Path socket) {
        final VarlinkServer server = new VarlinkServer(socket, INTERFACE)
                .describedBy(description())
                .reporting(org.fuin.sokar.app.SokarVersion.version());
        final TaskInventory inventory = new TaskInventory(context);

        // The same question 'sokar task list' asks, answered by the same code. A second
        // implementation is how the two come to disagree about what is running.
        server.method("List", (parameters, replies) -> {
            final List<Map<String, Object>> tasks =
                    inventory.tasks().stream().map(TaskInventory.Task::asMap).toList();
            replies.last(Map.of("tasks", tasks));
        });

        // Stop and Resume come from TaskControl, which is what 'sokar task stop' and
        // 'sokar task resume' render. The refusals are the reason that matters: one that exists
        // in the CLI and not here would be a task removed, over this socket, with work in it.
        final TaskControl control = new TaskControl(context);

        server.method("Stop", (parameters, replies) -> {
            final TaskControl.Stopped result = control.stop(text(parameters, "task"),
                    flag(parameters, "purge"), flag(parameters, "rescue"),
                    flag(parameters, "force"));
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("outcome", result.outcome().name());
            answer.put("work", result.work() == null ? "" : result.work());
            answer.put("rescuedRef", result.rescuedRef() == null ? "" : result.rescuedRef());
            answer.put("removed", result.removed());
            answer.put("helpers", result.helpers());
            answer.put("surviving", result.surviving());
            answer.put("detail", result.detail() == null ? "" : result.detail());
            answer.put("discarded", result.discarded());
            replies.last(answer);
        });

        server.method("Resume", (parameters, replies) -> {
            final TaskControl.Resumed result = control.resume(text(parameters, "task"));
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("outcome", result.outcome().name());
            answer.put("started", result.started());
            answer.put("recorded", result.recorded());
            answer.put("imageDrift", result.imageDrift() == null ? "" : result.imageDrift());
            answer.put("problems", result.problems());
            replies.last(answer);
        });

        // ------------------------------------------------------------------ streaming
        //
        // A client that polls lags a prompt that expires, which is why these exist at all. Both
        // end when the client goes away: sending to a closed connection throws, and that is the
        // only disconnect signal there is - nothing else tells a server that a reader left.

        server.method("Watch", (parameters, replies) -> {
            if (!replies.streaming()) {
                // Asked without 'more', which is a client wanting the state once. Answering with
                // the list rather than an error means one method serves both, and a client that
                // cannot stream is not locked out of the data.
                replies.last(Map.of("tasks", inventory.tasks().stream()
                        .map(TaskInventory.Task::asMap).toList()));
                return;
            }
            List<String> previous = null;
            while (true) {
                final List<TaskInventory.Task> tasks = inventory.tasks();
                // Only on change - and "change" cannot include the age. The runtime's state is a
                // phrase carrying one: 'Up 3 seconds' becomes 'Up 4 seconds' a second later, so
                // comparing the whole answer made this fire every second and told a fleet view to
                // redraw because a clock had moved. Measured against a real task, which is the
                // only way this shows up at all.
                final List<String> settled = tasks.stream().map(SokarDaemon::withoutAge).toList();
                if (!settled.equals(previous)) {
                    replies.more(Map.of("tasks",
                            tasks.stream().map(TaskInventory.Task::asMap).toList()));
                    previous = settled;
                }
                sleep(WATCH_INTERVAL);
            }
        });

        server.method("Sets", (parameters, replies) -> {
            // What a chooser offers. Scanned rather than listed anywhere: a set an operator added
            // is a file they dropped in a directory, and it has to appear without anything being
            // rebuilt.
            final var directory = context.paths().egressSets();
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("sets", directory.all().values().stream()
                    .map(set -> Map.<String, Object>of("name", set.name(), "label", set.label(),
                            "domains", set.domains()))
                    .toList());
            answer.put("locations", directory.locations().stream().map(Path::toString).toList());
            replies.last(answer);
        });

        server.method("Egress", (parameters, replies) -> {
            final Path file = projectFile(parameters);
            final EgressControl egress = new EgressControl(context);
            // Absent, not empty: a ?string that was not sent arrives as "" here, and an empty
            // agent name is not a request for the default - it is a request for an agent called
            // nothing, which is refused. Measured over the wire on the first call.
            final String agent = text(parameters, "agent");
            final String named = agent.isEmpty() ? null : agent;
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("hosts", hosts(egress.reachable(file, named)));
            answer.put("refused", egress.refused(named));
            replies.last(answer);
        });

        server.method("SetEgress", (parameters, replies) -> {
            final EgressControl.Effect effect = new EgressControl(context).apply(
                    projectFile(parameters),
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

        server.method("Projects", (parameters, replies) -> {
            // The path in each answer is the one thing a client cannot work out: over a forwarded
            // socket there is no filesystem on this side to look in, and every gate method takes
            // one.
            replies.last(Map.of("projects", new ProjectInventory(context).projects().stream()
                    .map(ProjectInventory.Summary::asMap).toList()));
        });

        server.method("Logs", (parameters, replies) -> {
            // Listed, not guessed: which files a task has depends on what it started, and a client
            // that knew the names would open an empty viewer for one that never existed.
            replies.last(Map.of("logs", new TaskInventory(context).logs(text(parameters, "task"))
                    .stream().map(TaskInventory.Log::asMap).toList()));
        });

        server.method("Tail", (parameters, replies) -> {
            final Path log = logOf(context, text(parameters, "task"), text(parameters, "log"));
            if (log == null) {
                throw new VarlinkException(INTERFACE + ".NoSuchLog",
                        Map.of("task", text(parameters, "task"), "log", text(parameters, "log")));
            }
            long position = 0;
            if (!replies.streaming()) {
                replies.last(Map.of("lines", read(log, 0).lines()));
                return;
            }
            while (true) {
                final Chunk chunk = read(log, position);
                if (!chunk.lines().isEmpty()) {
                    replies.more(Map.of("lines", chunk.lines()));
                }
                position = chunk.position();
                sleep(TAIL_INTERVAL);
            }
        });

        // ------------------------------------------------------------------ inventory
        //
        // Read-only answers about the machine, so an interface never shells out to the CLI to
        // find out what is installed or what is waiting.

        server.method("Agents", (parameters, replies) -> {
            // Each agent is a process this starts and handshakes with, so it is closed again:
            // leaving them running would leak one per call.
            try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
                final List<Map<String, Object>> found = agents.all().stream().map(agent -> {
                    final Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", agent.name());
                    entry.put("label", agent.definition().label());
                    entry.put("binary", agent.definition().binary());
                    entry.put("version", agent.definition().version() == null
                            ? "" : agent.definition().version());
                    entry.put("from", agent.executable().toString());
                    entry.put("allowedDomains", agent.definition().allowedDomains());
                    return entry;
                }).toList();
                // What could not be asked matters as much as what could: an agent that fails to
                // describe itself is installed and unusable, and silence would read as absent.
                replies.last(Map.of("agents", found, "failures", agents.failures()));
            }
        });

        server.method("Credentials", (parameters, replies) -> {
            // Names, types and lengths - never a value. The vault is read only if the passphrase
            // is already in the kernel keyring: a daemon has no terminal to ask at, and a call
            // that blocked on a prompt nobody can see would hang the interface.
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("vault", context.vault().path().toString());
            answer.put("exists", context.vault().exists());
            final List<Map<String, Object>> entries = context.credentials().entrySet().stream()
                    .map(entry -> {
                        final Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", entry.getKey());
                        row.put("type", entry.getValue().type() == null
                                ? "" : entry.getValue().type());
                        row.put("characters", entry.getValue().value().length());
                        return row;
                    }).toList();
            answer.put("credentials", entries);
            // Empty because it is locked and empty because it holds nothing are different things
            // an interface has to show apart.
            answer.put("readable", !context.vault().exists() || !entries.isEmpty()
                    || !context.credentials().isEmpty());
            replies.last(answer);
        });

        // ----------------------------------------------------------------------- the gate
        //
        // The one crossing where work leaves the machine. These are thin over GitGate, which is
        // what the CLI drives too, so an approval means the same thing from either.

        server.method("Pending", (parameters, replies) -> {
            final GitGate gate = gate(parameters);
            final java.time.Instant now = java.time.Instant.now();
            final List<Map<String, Object>> waiting = gate.pendingDetail().stream().map(push -> {
                final Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", push.name());
                row.put("commit", push.commit());
                row.put("subject", push.subject());
                row.put("waiting", push.lagText(now));
                row.put("at", push.at().toString());
                return row;
            }).toList();
            replies.last(Map.of("mirror", gate.mirror().toString(),
                    "mode", gate.mode().name().toLowerCase(),
                    "seededFrom", gate.seededFrom() == null ? "" : gate.seededFrom(),
                    "pending", waiting));
        });

        server.method("Review", (parameters, replies) -> {
            final GitGate gate = gate(parameters);
            final String name = text(parameters, "name");
            final String against = text(parameters, "against");
            replies.last(Map.of("diff", gate.review(name, against.isEmpty() ? null : against),
                    "log", gate.log(name, against.isEmpty() ? null : against)));
        });

        server.method("Approve", (parameters, replies) -> {
            // The single call that sends anything anywhere, and it makes the caller name where.
            final GitGate gate = gate(parameters);
            final String branch = text(parameters, "branch");
            if (branch.isEmpty()) {
                throw new VarlinkException(INTERFACE + ".BranchRequired",
                        Map.of("name", text(parameters, "name")));
            }
            gate.approve(text(parameters, "name"), branch);
            replies.last(Map.of("forwarded", text(parameters, "name"), "branch", branch));
        });

        server.method("Reject", (parameters, replies) -> {
            final GitGate gate = gate(parameters);
            gate.reject(text(parameters, "name"));
            replies.last(Map.of("rejected", text(parameters, "name")));
        });

        // ------------------------------------------------------------------ starting a task
        //
        // The one call that runs the CLI rather than calling into it, and the reason is written
        // down rather than glossed: 'task run' is seven hundred lines that build an image, mint a
        // token, install hooks, start four helpers in a fixed order and can hand over a terminal.
        // Extracting that the way stop and resume were extracted is the right end state; doing it
        // hastily to a command that is the whole product is not. Spawning it is behavior parity
        // by construction - it *is* the same code - at the cost of parsing one line of its output
        // for the container name.
        //
        // Started detached and never waited on as a child: a task must outlive whoever asked for
        // it, which is the property that lets this daemon be restarted while tasks run.

        server.method("Start", (parameters, replies) -> {
            // Into the domain, not out to a subprocess. Until TaskLaunch existed this spawned
            // 'sokar task run' and read one line of its output for the container name, because
            // running the command was the only way to start a task. Now the CLI and this call
            // the same object, so neither can grow a behavior the other lacks - and there is
            // because the name comes back as a value.
            final java.io.StringWriter collected = new java.io.StringWriter();
            final PrintWriter sink = replies.streaming()
                    // Streamed as it happens: building an image takes minutes, and an interface
                    // showing nothing for that long is indistinguishable from one that hung.
                    ? new PrintWriter(new StreamingWriter(replies), true)
                    : new PrintWriter(collected, true);

            final java.util.concurrent.atomic.AtomicReference<String> started =
                    new java.util.concurrent.atomic.AtomicReference<>("");
            final TaskLaunch launch = new TaskLaunch(context, request(parameters));
            final int code = launch.launch(sink, sink, running -> {
                        started.set(running.container());
                        // With a prompt this is an unattended run and has to actually run: a task
                        // started over the socket is as unattended as one started at a terminal,
                        // and a client that got "started" for a run nobody performed would wait
                        // for output that was never going to come. Without one, there is no
                        // terminal on the far end of a socket, so the task is up and that is all.
                        if (empty(parameters, "prompt") == null) {
                            return 0;
                        }
                        return launch.runAgent(running.runner(), running.selected(),
                                running.container(), running.environment(), sink, sink);
                    });
            sink.flush();
            replies.last(Map.of("container", started.get(), "exitCode", code,
                    "output", replies.streaming() ? List.of()
                            : List.of(collected.toString().split("\n", -1))));
        });

        // ------------------------------------------------------------- clearance prompts
        //
        // Every running task already serves its own prompts on its own socket - the watcher's
        // 'org.fuin.sokar.Clearance1'. This is the one socket an interface talks to instead of
        // discovering and connecting to each of them, and it is where lag actually costs
        // something: a prompt expires while a client polls, and the task stays blocked.

        server.method("Prompts", (parameters, replies) -> {
            if (!replies.streaming()) {
                // Answering a stream once would look like it worked and then deliver nothing
                // ever again. The watcher's own Subscribe refuses the same way.
                throw new VarlinkException(INTERFACE + ".StreamRequired",
                        Map.of("method", "Prompts"));
            }
            final java.util.concurrent.BlockingQueue<Map<String, Object>> events =
                    new java.util.concurrent.LinkedBlockingQueue<>(1024);
            final Map<String, Thread> watching = new java.util.concurrent.ConcurrentHashMap<>();
            try {
                while (true) {
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

        return server;
    }

    /**
     * Returns the interface description, read from the jar.
     * <p>
     * A resource rather than a string in this file, so that the contract is a document an
     * interface author can read, diff and be handed - and so that {@link SokarDaemonTest} can
     * hold it against the methods actually registered here. Missing it is a broken build rather
     * than a daemon that answers {@code InterfaceNotFound}: the description is not optional.
     *
     * @return The IDL.
     */
    public static String description() {
        try (java.io.InputStream in =
                SokarDaemon.class.getResourceAsStream("/varlink/" + INTERFACE + ".varlink")) {
            if (in == null) {
                throw new IllegalStateException(
                        "The interface description is not on the classpath: /varlink/"
                                + INTERFACE + ".varlink");
            }
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read the interface description", ex);
        }
    }

    /**
     * Returns hosts and their origins as a client reads them.
     *
     * @param origins Host to origin.
     * @return One entry per host, in the order they were granted.
     */
    private static List<Map<String, Object>> hosts(Map<String, String> origins) {
        return origins.entrySet().stream()
                .map(entry -> Map.<String, Object>of("host", entry.getKey(),
                        "origin", entry.getValue()))
                .toList();
    }

    /**
     * Returns a list of strings a call carried, empty when it carried none.
     *
     * @param parameters What the call carried.
     * @param name Parameter to read.
     * @return The values, never {@code null}.
     */
    private static List<String> strings(Map<String, Object> parameters, String name) {
        if (!(parameters.get(name) instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    /**
     * Returns the project file a call names, refusing a call that names none.
     * <p>
     * The same rule the gate methods follow: a path the caller gives, which it got from
     * {@code Projects} rather than invented.
     *
     * @param parameters What the call carried.
     * @return The project file.
     */
    private static Path projectFile(Map<String, Object> parameters) {
        final String file = text(parameters, "project");
        if (file.isEmpty()) {
            throw new VarlinkException(INTERFACE + ".ProjectRequired", Map.of());
        }
        return Path.of(file);
    }

    /**
     * Returns the gate of the project a call names.
     * <p>
     * The project file is a path the caller gives, the way it gives one to the CLI: a gate
     * belongs to a project rather than to a task, and this daemon serves whatever projects the
     * operator has. Reading it fails loudly rather than answering about the wrong gate.
     *
     * @param parameters The call's parameters.
     * @return The gate, initialized.
     */
    private static GitGate gate(Map<String, Object> parameters) {
        final String file = text(parameters, "project");
        if (file.isEmpty()) {
            throw new VarlinkException(INTERFACE + ".ProjectRequired", Map.of());
        }
        final Project project = GateSupport.project(Path.of(file));
        final String upstream = text(parameters, "upstream");
        final GitGate gate = GateSupport.gate(project, upstream.isEmpty() ? null : upstream);
        gate.initialize();
        return gate;
    }

    /**
     * Subscribes to the prompts of every running task that is not already being watched.
     * <p>
     * Rescanned rather than taken once: a task started after a client connected raises prompts
     * too, and a client that had to reconnect to see them would miss exactly the ones that arrive
     * while an agent is doing something new.
     *
     * @param context Where the paths come from.
     * @param inventory What tasks exist.
     * @param events Where events from every task go.
     * @param watching Subscriptions already running, by task name.
     */
    private static void subscribe(SokarContext context, TaskInventory inventory,
            java.util.concurrent.BlockingQueue<Map<String, Object>> events,
            Map<String, Thread> watching) {

        watching.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        for (final TaskInventory.Task task : inventory.tasks()) {
            if (!task.running() || watching.containsKey(task.name())) {
                continue;
            }
            final Path socket = clearanceSocket(context, task.name());
            if (socket == null) {
                continue;
            }
            watching.put(task.name(), Thread.ofVirtual().start(() -> {
                try (VarlinkClient client = new VarlinkClient(socket)) {
                    client.callMore(ClearanceService.INTERFACE + ".Subscribe", Map.of(), event -> {
                        final Map<String, Object> tagged = new LinkedHashMap<>(event);
                        // Which task it came from: one socket now carries the prompts of every
                        // task, and an answer has to go back to the one that asked.
                        tagged.put("task", task.name());
                        // And what to answer with. The watcher keys its pending decisions by
                        // this and the event does not carry it, so a client had to rebuild it
                        // from the other fields - a separator away from answering a prompt that
                        // does not exist while the task stays blocked.
                        tagged.put("key", ClearanceService.key(
                                String.valueOf(event.get("protocol")),
                                String.valueOf(event.get("destination")),
                                event.get("port") instanceof Number port ? port.intValue() : 0));
                        // offer, not put: a client that stopped reading must not block the
                        // subscription, which is on the path of every dropped packet.
                        events.offer(tagged);
                        return !Thread.currentThread().isInterrupted();
                    });
                } catch (RuntimeException | java.io.IOException ex) {
                    // The watcher went away, which is how a task ending looks from here.
                }
            }));
        }
    }

    /**
     * Returns a running task's clearance socket, or {@code null} when it has none.
     *
     * @param context Where the paths come from.
     * @param task Container name.
     * @return The socket, or {@code null}.
     */
    private static Path clearanceSocket(SokarContext context, String task) {
        if (!ContainerName.isSokar(task)) {
            return null;
        }
        final Path socket = context.paths().containerState(task).resolve("clearance.sock");
        return Files.exists(socket) ? socket : null;
    }

    /**
     * Returns what a watcher compares: everything about a task except how long it has been that
     * way.
     * <p>
     * The exit code stays, because a task that was stopped and one that died are different things
     * a fleet view must show apart; the age goes, because it changes on its own.
     *
     * @param task The task.
     * @return A key that changes only when something an operator would want redrawn changes.
     */
    private static String withoutAge(TaskInventory.Task task) {
        final String state = task.state();
        final int code = state.startsWith("Exited (") ? state.indexOf(')') : -1;
        final String settled = code > 0 ? state.substring(0, code + 1)
                : state.split(" ")[0];
        return String.join("\u0000", task.name(), String.valueOf(task.project()),
                String.valueOf(task.securityClass()), settled, String.valueOf(task.running()),
                String.valueOf(task.helpers()),
                // What the work is doing changes without the container changing at all - a task
                // that starts waiting for an answer looks identical to the runtime - so a watcher
                // that did not compare this would never redraw the one transition that matters.
                String.valueOf(task.activity()), String.valueOf(task.waitingFor()),
                String.valueOf(task.agent()), String.valueOf(task.mode()),
                String.valueOf(task.branch()), String.valueOf(task.clearance()));
    }

    /**
     * Returns the log file of one task, or {@code null} when there is no such thing.
     * <p>
     * The name is checked against the files that are actually there rather than resolved as a
     * path: a client is not this process, and {@code ../../etc/passwd} is a log name until
     * something refuses it.
     *
     * @param context Where the paths come from.
     * @param task Container name.
     * @param log File name within the task's state directory.
     * @return The file, or {@code null}.
     */
    private static Path logOf(SokarContext context, String task, String log) {
        if (!ContainerName.isSokar(task) || log.isEmpty() || !log.endsWith(".log")
                || log.contains("/") || log.contains("..")) {
            return null;
        }
        final Path file = context.paths().containerState(task).resolve(log);
        return Files.isRegularFile(file) ? file : null;
    }

    /** What one read of a log returned, and where to carry on from. */
    private record Chunk(List<String> lines, long position) {
    }

    /**
     * Reads what a log has gained since the last read.
     * <p>
     * By position rather than by watching the file: a log is appended to by another process
     * entirely - a helper Sokar started - and the cheapest correct answer to "what is new" is how
     * much longer it got. A file that shrank was rotated or replaced, and is read from the start.
     *
     * @param log The file.
     * @param from Where the previous read stopped.
     * @return The new lines and the new position.
     */
    private static Chunk read(Path log, long from) {
        try {
            final long size = Files.size(log);
            final long start = size < from ? 0 : from;
            if (size == start) {
                return new Chunk(List.of(), start);
            }
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(log.toFile(), "r")) {
                file.seek(start);
                final byte[] buffer = new byte[(int) Math.min(size - start, MAX_CHUNK)];
                file.readFully(buffer);
                final String text = new String(buffer, java.nio.charset.StandardCharsets.UTF_8);
                // A partial last line is left for the next read: half a line is worse than a
                // slightly later whole one, and a log is written a line at a time.
                final int end = text.lastIndexOf('\n');
                if (end < 0) {
                    return new Chunk(List.of(), start);
                }
                return new Chunk(List.of(text.substring(0, end).split("\n", -1)),
                        start + text.substring(0, end + 1)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
        } catch (java.io.IOException ex) {
            return new Chunk(List.of(), from);
        }
    }

    private static void sleep(java.time.Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new VarlinkException("interrupted");
        }
    }

    /**
     * Turns a call's parameters into a launch request.
     * <p>
     * Only what the caller named is taken; everything else keeps the value the record was
     * declared with, so the CLI's defaults stay the only defaults. Restating them here would be
     * a second place for them to drift.
     *
     * @param parameters The call's parameters.
     * @return What to start.
     */
    private static TaskLaunch.Request request(Map<String, Object> parameters) {
        final String task = text(parameters, "task");
        final String project = text(parameters, "project");
        return new TaskLaunch.Request(
                task.isEmpty() ? "shell" : task,
                Path.of(project.isEmpty() ? "project.yml" : project),
                empty(parameters, "agent"), empty(parameters, "provider"),
                empty(parameters, "credentialType"),
                parameters.get("tokenHours") instanceof Number hours ? hours.intValue() : 8,
                empty(parameters, "upstream"), flag(parameters, "noGate"),
                flag(parameters, "dryRun"),
                text(parameters, "clearance").isEmpty() ? "prompt"
                        : text(parameters, "clearance"),
                flag(parameters, "keep"),
                // The same vocabulary the CLI uses, taken from the caller rather than guessed at:
                // a task started over the socket is as much a shell, a session or an unattended
                // run as one started at the machine, and it has to say which afterwards.
                org.fuin.sokar.wire.TaskMode.parse(text(parameters, "mode"),
                        empty(parameters, "prompt") == null
                                ? org.fuin.sokar.wire.TaskMode.SHELL
                                : org.fuin.sokar.wire.TaskMode.UNATTENDED),
                empty(parameters, "prompt"),
                empty(parameters, "model"),
                parameters.get("maxTurns") instanceof Number turns ? turns.intValue() : null,
                parameters.get("minutes") instanceof Number minutes ? minutes.intValue()
                        : TaskLaunch.DEFAULT_MINUTES);
    }

    /**
     * Reads an optional string parameter as {@code null} rather than as the empty string.
     *
     * @param parameters The call's parameters.
     * @param name Parameter to read.
     * @return Its value, or {@code null} when it was not given.
     */
    private static @org.jspecify.annotations.Nullable String empty(Map<String, Object> parameters,
            String name) {
        final String value = text(parameters, name);
        return value.isEmpty() ? null : value;
    }

    /**
     * Sends whatever the launch prints as it prints it.
     * <p>
     * A writer rather than a collected string, because the point of streaming is that the client
     * sees the image build while it happens.
     */
    private static final class StreamingWriter extends java.io.Writer {

        private final VarlinkServer.Replies replies;

        private final StringBuilder line = new StringBuilder();

        /** Set once the client has gone, after which this stops trying to reach it. */
        private boolean gone;

        StreamingWriter(VarlinkServer.Replies replies) {
            this.replies = replies;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            line.append(buffer, offset, length);
            int end;
            while ((end = line.indexOf("\n")) >= 0) {
                final String complete = line.substring(0, end);
                line.delete(0, end + 1);
                if (gone) {
                    continue;
                }
                try {
                    replies.more(Map.of("line", complete));
                } catch (java.io.IOException ex) {
                    // The client left mid-build. Measured: throwing here aborted the launch and
                    // no container was ever created - a task canceled because whoever asked for
                    // it closed a window. The start is not this connection's to cancel, so this
                    // stops reporting and lets it finish; 'List' shows it afterwards.
                    gone = true;
                }
            }
        }

        @Override
        public void flush() {
            // Lines are sent as they complete; a partial one waits for its newline.
        }

        @Override
        public void close() {
            // Nothing to release: the connection belongs to the server.
        }
    }

    /**
     * Reads a string parameter, treating a missing one as empty.
     * <p>
     * A client is not this process, so nothing it sends can be assumed to be there or to be of
     * the type this expects. An absent task name reaches {@code TaskControl} as the empty string
     * and is refused there as a name Sokar did not create, which is the same answer the CLI gives.
     *
     * @param parameters What the call carried.
     * @param name Parameter to read.
     * @return Its value, or the empty string.
     */
    private static String text(Map<String, Object> parameters, String name) {
        final Object value = parameters.get(name);
        return value instanceof String string ? string : "";
    }

    /**
     * Reads a boolean parameter, treating anything else as false.
     *
     * @param parameters What the call carried.
     * @param name Parameter to read.
     * @return Its value, or {@code false}.
     */
    private static boolean flag(Map<String, Object> parameters, String name) {
        return Boolean.TRUE.equals(parameters.get(name));
    }

    /**
     * Runs the daemon until it is stopped.
     *
     * @param args Ignored; the socket location comes from the environment, like everything else.
     */
    public static void main(final String[] args) {
        final SokarContext context = SokarContext.real();
        final Path socket = context.paths().daemonSocket();
        try (VarlinkServer server = serving(context, socket)) {
            System.out.println("sokard listening on " + socket);
            System.out.flush();
            server.run();
        } catch (RuntimeException ex) {
            System.err.println("sokard: " + ex.getMessage());
            System.err.flush();
            System.exit(70);
        }
    }
}
