package org.fuin.sokar.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.GateSupport;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskLaunch;
import org.fuin.sokar.app.ProjectInventory;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.clearance.ClearanceService;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.runtime.ContainerName;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.fuin.sokar.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

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

    /**
     * How often a project scan is repeated, and deliberately slower than {@link #WATCH_INTERVAL}.
     * <p>
     * A scan runs {@code podman} and reads the gate's refs for every project, so half a second
     * would spend subprocesses continuously on answers that change on human timescales - a project
     * created, a push arriving at the gate, an environment prepared. The contract names the one
     * place where latency costs something real, and it is {@code Prompts} rather than this: work
     * waiting at the gate has no deadline and sitting there for days is not a failure.
     */
    static final java.time.Duration PROJECT_WATCH_INTERVAL = java.time.Duration.ofSeconds(3);

    /** How long a prompt stream waits before looking for newly started tasks. */
    static final java.time.Duration PROMPT_INTERVAL = java.time.Duration.ofMillis(500);

    /** How often a tail looks for new lines. */
    static final java.time.Duration TAIL_INTERVAL = java.time.Duration.ofMillis(200);

    /** Most bytes one tail reply carries, so a huge log cannot become one huge message. */
    static final long MAX_CHUNK = 64 * 1024;

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
        // One for the daemon's life: what it derives about waiting for a person is read at a pace that is
        // cheap only when the same instance is asked again.
        final TaskInventory inventory = TaskInventory.deriving(context);

        // Stop and Remove come from TaskControl, which is what 'sokar task stop' and
        // 'sokar task remove' render. The refusals are the reason that matters: one that exists
        // in the CLI and not here would be a task removed, over this socket, with work in it.
        final TaskControl control = new TaskControl(context);

        // Streaming:
        // A client that polls lags a prompt that expires, which is why these exist at all. Each
        // ends when its client goes away: it asks between its waits whether the client is still
        // there. Sending to a closed connection used to be the only sign, so a stream with no news
        // ran for the daemon's life after its client had reconnected.

        // Each area registers its own methods, from its own file.
        TaskMethods.register(server, context, inventory, control);
        ProjectMethods.register(server, context, inventory, control);
        EgressMethods.register(server, context, inventory, control);
        VaultMethods.register(server, context, inventory, control);
        GateMethods.register(server, context, inventory, control);
        AgentMethods.register(server, context, inventory, control);
        MessagingMethods.register(server, context, inventory, control);
        MachineMethods.register(server, context, inventory, control);
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
        // One interface on the wire, written in parts: each area's methods, types and errors in a file of its own,
        // so a change to one area's contract is a change to its own file. The order is fixed here, not found.
        final StringBuilder description = new StringBuilder();
        for (final String part : PARTS) {
            final String name = "/varlink/" + INTERFACE + "/" + part + ".varlink";
            try (java.io.InputStream in = SokarDaemon.class.getResourceAsStream(name)) {
                if (in == null) {
                    throw new IllegalStateException("The interface description is not on the classpath: " + name);
                }
                description.append(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("Cannot read the interface description", ex);
            }
        }
        return description.toString();
    }

    /** The parts of the interface description, in the order they are served: the header first. */
    static final List<String> PARTS = List.of("00-header", "10-common", "15-task", "20-project", "25-egress", "30-vault", "35-gate", "40-agent", "45-messaging", "50-machine");

    /**
     * Returns hosts and their origins as a client reads them.
     *
     * @param origins Host to origin.
     * @return One entry per host, in the order they were granted.
     */
    static List<Map<String, Object>> hosts(Map<String, String> origins) {
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
    static List<String> strings(Map<String, Object> parameters, String name) {
        if (!(parameters.get(name) instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    /**
     * Returns the project file a call names, refusing a call that names none.
     * <p>
     * <strong>A name, not a path.</strong> A client cannot see this machine's filesystem, so it
     * has no path to invent - and which project a call is about must not depend on a directory.
     * Where the file comes from is the machine's answer, and it prefers the one it verified.
     *
     * @param parameters What the call carried.
     * @param context The machine.
     * @return The project file.
     */
    static Path projectFile(Map<String, Object> parameters, SokarContext context) {
        final String name = text(parameters, "project");
        if (name.isEmpty()) {
            throw new VarlinkException(INTERFACE + ".ProjectRequired", Map.of());
        }
        try {
            return org.fuin.sokar.app.ProjectSource.require(context, name);
        } catch (final org.fuin.sokar.core.project.ProjectException ex) {
            throw new VarlinkException(INTERFACE + ".Failed",
                    Map.of("message", String.valueOf(ex.getMessage())));
        }
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
    static GitGate gate(Map<String, Object> parameters, SokarContext context) {
        final Project project = GateSupport.project(projectFile(parameters, context));
        final String upstream = address(parameters, "upstream");
        final GitGate gate = GateSupport.gate(context, project,
                GateSupport.repository(project, text(parameters, "repository")),
                upstream.isEmpty() ? null : upstream, null);
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
    static void subscribe(SokarContext context, TaskInventory inventory,
            org.fuin.sokar.clearance.Backlog events,
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
                        // Never waits: a client that stopped reading must not block the
                        // subscription, which is on the path of every dropped packet. What does
                        // not fit is counted and told to the client.
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
    static @Nullable Path clearanceSocket(SokarContext context, String task) {
        if (!ContainerName.isTask(task)) {
            return null;
        }
        final Path socket = context.paths().tasks().containerState(task).resolve("clearance.sock");
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
    static String withoutAge(TaskInventory.Task task) {
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
                String.valueOf(task.branch()), String.valueOf(task.clearance()),
                // Derived from the agent's own output, and just as invisible to the runtime: an agent that
                // puts a question to the person changes nothing but its screen.
                String.valueOf(task.derived().screen()), task.derived().waitingFor(),
                String.valueOf(task.derived().unproven()), task.derived().lastMessage(),
                String.valueOf(task.derived().asked()), task.derived().session());
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
    static @Nullable Path logOf(SokarContext context, String task, String log) {
        // The same rule the listing uses, so a client is never offered a file this refuses.
        if (!ContainerName.isTask(task) || log.isEmpty()
                || !org.fuin.sokar.app.TaskInventory.isLog(log)
                || log.contains("/") || log.contains("..")) {
            return null;
        }
        final Path file = context.paths().tasks().containerState(task).resolve(log);
        return Files.isRegularFile(file) ? file : null;
    }

    /** What one read of a log returned, and where to carry on from. */
    record Chunk(List<String> lines, long position) {
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
    /**
     * Returns where the last lines of a log start, read back from its end in blocks.
     * <p>
     * A client following a log asked for it from its first line, and a log of 53 MB crossed the socket before anything
     * was shown: the interface closed under it. A newline that ends the file ends the last line and starts none.
     *
     * @param log The file.
     * @param lines How many lines from the end.
     * @return The byte position of the first of them; the file's size for none, its start when it holds fewer.
     */
    static long startOfLast(Path log, int lines) {
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(log.toFile(), "r")) {
            final long size = file.length();
            if (lines <= 0 || size == 0) {
                return size;
            }
            file.seek(size - 1);
            long end = file.read() == '\n' ? size - 1 : size;
            int found = 0;
            final byte[] block = new byte[64 * 1024];
            while (end > 0) {
                final int length = (int) Math.min(block.length, end);
                final long from = end - length;
                file.seek(from);
                file.readFully(block, 0, length);
                for (int i = length - 1; i >= 0; i--) {
                    if (block[i] == '\n' && ++found == lines) {
                        return from + i + 1;
                    }
                }
                end = from;
            }
            return 0;
        } catch (java.io.IOException ex) {
            return 0;
        }
    }

    static Chunk read(Path log, long from) {
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
                // Counted in bytes, never by encoding decoded text again: a byte that is not UTF-8 decodes to a
                // character that encodes longer, and the position then lay past the end of the file.
                int end = buffer.length - 1;
                while (end >= 0 && buffer[end] != '\n') {
                    end--;
                }
                if (end < 0) {
                    // A partial last line is left for the next read - unless the whole read was one, which no later
                    // read could pass either: then it goes as it is.
                    if (buffer.length < MAX_CHUNK) {
                        return new Chunk(List.of(), start);
                    }
                    return new Chunk(List.of(new String(buffer, java.nio.charset.StandardCharsets.UTF_8)),
                            start + buffer.length);
                }
                final String text = new String(buffer, 0, end, java.nio.charset.StandardCharsets.UTF_8);
                return new Chunk(List.of(text.split("\n", -1)), start + end + 1);
            }
        } catch (java.io.IOException ex) {
            return new Chunk(List.of(), from);
        }
    }

    static void sleep(java.time.Duration duration) {
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
    /**
     * Returns one install artifact as plain values.
     *
     * @param artifact What the agent declares.
     * @return The artifact, with {@code null} as "" so every encoding can carry it.
     */
    /**
     * Returns one provider as an interface reads it.
     * <p>
     * Separated from the call so the key resolution can be exercised: the interesting case is a
     * vault written before credentials were keyed by provider, which no fixture of an empty vault
     * reaches. A test that only saw an empty vault would pass whether this used the resolved key
     * or the provider's own name, because there they are the same string.
     *
     * @param provider The provider.
     * @param stored What the vault holds, by name.
     * @param legacyAgent An agent whose own name this provider's credential may still be under,
     *        or {@code null} when there is none.
     * @return The row, carrying no credential value.
     */
    static Map<String, Object> provider(org.fuin.sokar.agent.api.ProviderDefinition provider,
            Map<String, org.fuin.sokar.vault.VaultEntry> stored,
            @org.jspecify.annotations.Nullable String legacyAgent) {
        return org.fuin.sokar.app.ProviderInventory.row(provider, stored, legacyAgent);
    }

    static Map<String, Object> artifact(
            org.fuin.sokar.agent.api.InstallArtifact artifact) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("url", artifact.url());
        row.put("sha256", artifact.sha256() == null ? "" : artifact.sha256());
        row.put("target", artifact.target());
        row.put("unverified", artifact.unverified());
        row.put("reason", artifact.reason() == null ? "" : artifact.reason());
        return row;
    }

    /**
     * Returns the log line one Start leaves behind.
     * <p>
     * Names and the outcome only: a request can carry a prompt, and a log is not where that goes.
     *
     * @param task The task name asked for, or empty for the default.
     * @param container The container it produced or found, or empty when there is none.
     * @param code The exit code the caller was given.
     * @return One line.
     */
    static String startLine(String task, String container, int code) {
        final String name = !container.isEmpty() ? container : task.isEmpty() ? "shell" : task;
        return "start " + name + ": exit " + code;
    }

    /** How often the talk stream looks for new record lines. */
    static final java.time.Duration TALK_INTERVAL = java.time.Duration.ofSeconds(2);

    static org.fuin.sokar.app.Mailbox mailboxOf(SokarContext context,
            Map<String, Object> parameters) {
        final String task = text(parameters, "task");
        if (!org.fuin.sokar.runtime.ContainerName.isTask(task)) {
            // '' and '..' resolved to directories that exist; a name that is no task's is answered as one.
            throw new VarlinkException(INTERFACE + ".NoSuchTask", Map.of("task", task));
        }
        final org.fuin.sokar.app.Mailbox mailbox =
                new org.fuin.sokar.app.Mailbox(context.paths().messaging().mailbox(task));
        if (!mailbox.exists()) {
            throw new VarlinkException(INTERFACE + ".NoSuchTask", Map.of("task", task));
        }
        return mailbox;
    }

    /**
     * Renders a keyslot for the wire.
     * <p>
     * {@code self} is the one field the vault does not know: it is about this session, not about
     * the file. It is true for the slot whose share is the one currently held here, which is what
     * lets a list mark "this device".
     *
     * @param slot The slot.
     * @param context The machine.
     * @return The object an interface reads.
     */
    static Map<String, Object> slotAsMap(org.fuin.sokar.vault.Keyslot slot,
            SokarContext context) {
        final java.util.Optional<byte[]> held =
                org.fuin.sokar.app.VaultShare.held(context.paths());
        final boolean self = !slot.recovery() && held.isPresent()
                && context.vault().takes(slot.id(), held.get());
        return Map.of("id", slot.id(), "name", slot.name(), "storage", slot.storage(),
                "enrolled", slot.enrolled(), "lastUsed", slot.lastUsed(),
                "self", self, "recovery", slot.recovery());
    }

    static List<Map<String, Object>> destinations(SokarContext context) {
        final java.util.Set<String> seen = new java.util.HashSet<>();
        return org.fuin.sokar.app.Destination.declared(context.paths().xdg().data()).stream()
                .map(declared -> destinationRow(declared.destination(), declared.file(), declared.packaged(),
                        seen.add(declared.destination().name())))
                .toList();
    }

    static Map<String, Object> destinationRow(org.fuin.sokar.app.Destination destination,
            java.nio.file.Path file, boolean packaged, boolean inForce) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", destination.name());
        row.put("label", destination.label());
        row.put("upstream", destination.upstream());
        row.put("authHeader", destination.authHeader());
        row.put("authPrefix", destination.authPrefix());
        row.put("authQuery", destination.authQuery() == null ? "" : destination.authQuery());
        row.put("file", file.toString());
        row.put("packaged", packaged);
        row.put("inForce", inForce);
        return row;
    }

    static Map<String, String> credentials(Map<String, Object> parameters) {
        final Map<String, String> credentials = new LinkedHashMap<>();
        if (parameters.get("credentials") instanceof Map<?, ?> named) {
            named.forEach((entry, destination) -> credentials.put(String.valueOf(entry), String.valueOf(destination)));
        }
        return credentials;
    }

    static TaskLaunch.Request request(Map<String, Object> parameters,
            SokarContext context) {
        final String task = text(parameters, "task");
        final String repository = text(parameters, "repository");
        return new TaskLaunch.Request(
                // Unnamed: after its repository, as at the command line.
                !task.isEmpty() ? task : repository.isEmpty() ? "shell"
                        : org.fuin.sokar.app.TaskNames.fromRepository(context, text(parameters, "project"), repository),
                // A name, resolved here rather than taken as a path. The machine prefers the file
                // it verified; a client has no filesystem here to point at one.
                projectFile(parameters, context),
                empty(parameters, "agent"), empty(parameters, "provider"),
                empty(parameters, "credentialType"),
                parameters.get("tokenHours") instanceof Number hours ? hours.intValue() : 8,
                address(parameters, "upstream").isEmpty() ? null : address(parameters, "upstream"),
                flag(parameters, "noGate"),
                flag(parameters, "dryRun"),
                text(parameters, "clearance").isEmpty() ? "prompt"
                        : text(parameters, "clearance"),
                // Inverted: the contract asks whether to REMOVE, and the request records whether
                // to keep. The default flipped with the verb - what a caller reaches for first
                // used to destroy what it had just made.
                !flag(parameters, "rm"),
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
                        : TaskLaunch.DEFAULT_MINUTES,
                // Not defaulted here. A project is a unit of work over one or more repositories,
                // and a client that does not say which one the task is for gets the same refusal
                // the CLI gives, naming what there is to choose from.
                empty(parameters, "repository"),
                credentials(parameters));
    }

    /**
     * Reads an optional string parameter as {@code null} rather than as the empty string.
     *
     * @param parameters The call's parameters.
     * @param name Parameter to read.
     * @return Its value, or {@code null} when it was not given.
     */
    static @org.jspecify.annotations.Nullable String empty(Map<String, Object> parameters,
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
    static final class StreamingWriter extends java.io.Writer {

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
    /**
     * Returns one credential record as plain values, with whether its value is there.
     * <p>
     * Built from the listing rather than by hand, so a record answered by one method and the same
     * record answered by another cannot drift.
     *
     * @param declarations Where the records are.
     * @param credential The one to describe.
     * @return The record, never its secret.
     */
    static Map<String, Object> connectionAsMap(
            org.fuin.sokar.app.CredentialDeclarations declarations,
            org.fuin.sokar.core.credential.Credential credential) {
        return declarations.asMaps().stream()
                .filter(row -> credential.match().equals(row.get("match"))
                        && credential.purpose().equals(row.get("purpose")))
                .findFirst()
                .orElseGet(() -> {
                    // Not in the file: a credential found by the names a git URL implies. It is
                    // real and usable, and saying nothing about it would make an interface show
                    // "no credential" for a machine that has one.
                    final Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("id", credential.id());
                    row.put("kind", credential.kind().name());
                    row.put("match", credential.match());
                    row.put("user", credential.user() == null ? "" : credential.user());
                    row.put("purpose", credential.purpose());
                    row.put("source", credential.source().name());
                    row.put("protected", credential.protectedHere());
                    // Asked, not assumed. This branch is for a record that is not in the file -
                    // one found by the names a git URL implies, or one being dry-run before it
                    // exists - and answering 'true' for both made a dry run say MISSING_VALUE
                    // and 'present: true' in the same breath.
                    row.put("present", declarations.holdsValue(credential));
                    row.put("expires", credential.expires() == null ? "" : credential.expires());
                    return row;
                });
    }

    /**
     * Returns a project's row, with its conversation when it has one.
     *
     * @param context Where the state is.
     * @param summary The project.
     * @return The row.
     */
    static Map<String, Object> projectRow(SokarContext context, ProjectInventory.Summary summary) {
        final Map<String, Object> row = summary.asMap();
        if (summary.file() == null) {
            return row;
        }
        try {
            final org.fuin.sokar.core.project.Project project =
                    org.fuin.sokar.core.project.ProjectReader.read(java.nio.file.Path.of(summary.file()));
            final Map<String, Object> messages = org.fuin.sokar.app.Conversations.row(context, project);
            if (messages != null) {
                final Map<String, Object> with = new LinkedHashMap<>(row);
                with.put("messages", messages);
                return with;
            }
        } catch (RuntimeException ex) {
            // A project file that cannot be read has no conversation to show.
        }
        return row;
    }

    /**
     * Returns a project that has a conversation, or refuses.
     *
     * @param context Where the projects are.
     * @param name The project.
     * @return The project.
     */
    static org.fuin.sokar.core.project.Project conversational(SokarContext context, String name) {
        final org.fuin.sokar.core.project.Project project;
        try {
            project = org.fuin.sokar.app.GateSupport.byName(context, name);
        } catch (RuntimeException ex) {
            throw new VarlinkException(INTERFACE + ".NoSuchProject", Map.of("project", name));
        }
        if (project.mail().conversations().isEmpty()) {
            throw new VarlinkException(INTERFACE + ".NoConversation", Map.of("project", name));
        }
        return project;
    }

    /**
     * Returns this machine's public message key, as a project's {@code machine-signers} names it.
     *
     * @param context This machine.
     * @return {@code principal}, {@code key}, {@code line} and {@code fingerprint}.
     */
    static Map<String, Object> messageKey(SokarContext context) {
        final String principal = "sokar@" + hostName();
        try {
            final org.fuin.sokar.vault.SigningKey key =
                    org.fuin.sokar.app.HostKey.loadOrCreate(context.paths().messaging().messageKey(), principal);
            final String line = org.fuin.sokar.app.HostKey.allowedSignersLine(key, principal);
            final String publicKey = line.substring(line.indexOf(' ') + 1);
            final String fingerprint = org.fuin.sokar.app.SignedBy.fingerprintOf(publicKey);
            return Map.of("principal", principal, "key", publicKey, "line", line,
                    "fingerprint", fingerprint == null ? "" : fingerprint);
        } catch (java.io.IOException ex) {
            throw new VarlinkException(INTERFACE + ".Failed", Map.of("message", String.valueOf(ex.getMessage())));
        }
    }

    static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "localhost";
        }
    }

    static String text(Map<String, Object> parameters, String name) {
        final Object value = parameters.get(name);
        return value instanceof String string ? string : "";
    }

    /**
     * Reads a parameter that becomes an argument of another program - a URL, an upstream, a host - refusing one
     * that would be read there as an option.
     * <p>
     * git and ssh-keyscan take these as positional arguments, and a value starting with a dash is an option to
     * them: {@code --upload-pack=...} is a command git runs.
     *
     * @param parameters What the call carried.
     * @param name Parameter to read.
     * @return Its value, or "" when absent.
     * @throws VarlinkException {@code InvalidParameter} for a value that starts with a dash.
     */
    static String address(Map<String, Object> parameters, String name) {
        final String value = text(parameters, name);
        if (value.startsWith("-")) {
            throw new VarlinkException("org.varlink.service.InvalidParameter", Map.of("parameter", name));
        }
        return value;
    }

    /**
     * Reads a boolean parameter, treating anything else as false.
     *
     * @param parameters What the call carried.
     * @param name Parameter to read.
     * @return Its value, or {@code false}.
     */
    /**
     * Reads an optional string parameter, answering {@code null} when it was not given.
     * <p>
     * Distinct from {@link #text}, which answers "". Here the difference carries meaning: no agent
     * named means "the only one installed", while an empty name is a name nothing matches.
     *
     * @param parameters What the call carried.
     * @param name Parameter to read.
     * @return Its value, or {@code null}.
     */
    @org.jspecify.annotations.Nullable
    static String absent(Map<String, Object> parameters, String name) {
        return parameters.get(name) instanceof String string && !string.isBlank() ? string : null;
    }

    static boolean flag(Map<String, Object> parameters, String name) {
        return Boolean.TRUE.equals(parameters.get(name));
    }

    /**
     * Runs the daemon until it is stopped.
     *
     * @param args Ignored; the socket location comes from the environment, like everything else.
     */
    /** What sokard prints when asked what it is, rather than starting. */
    static final String USAGE = """
            Usage: sokard [--help] [--version]

            Serves the Sokar domain over an owner-only unix socket, for an interface on this
            machine or one reached through an ssh forward. It takes no options: what it serves,
            and where, comes from the same configuration the CLI reads.

            A remote interface reaches it with 'sokar daemon connect' as an ssh ProxyCommand;
            the daemon itself never binds a network port.""";

    /**
     * Answers an argument without starting anything, or says there is nothing to answer.
     * <p>
     * <strong>It used to ignore every argument and run.</strong> So {@code sokard --help} started
     * a daemon - reported by the interface agent on 2026-09-12, after asking a binary what it does
     * left a second Sokar listening on that account's runtime socket. Anything a person types to
     * find out what something is must not be the thing that starts it.
     *
     * @param args What was passed.
     * @param out Where an answer goes.
     * @param err Where a refusal goes.
     * @return The exit code to use, or {@code null} to start the daemon.
     */
    static java.lang.@org.jspecify.annotations.Nullable Integer answer(String[] args,
            java.io.PrintStream out, java.io.PrintStream err) {
        if (args.length == 0) {
            return null;
        }
        // Checked before anything is answered, so that order cannot decide the outcome. Acting on
        // the first recognized option meant '--version --deamon' printed a version and exited 0,
        // reporting success for a command line that contains a mistake.
        for (final String argument : args) {
            if (!java.util.List.of("--help", "-h", "--version", "-V").contains(argument)) {
                // Refused rather than ignored. An argument somebody meant, spelled wrong, used to
                // start a daemon that did not do the thing they asked for.
                err.println("sokard: unknown option '" + argument + "'");
                err.println(USAGE);
                err.flush();
                return 64;
            }
        }
        if (java.util.List.of(args).contains("--help") || java.util.List.of(args).contains("-h")) {
            out.println(USAGE);
            out.flush();
            return 0;
        }
        out.println("sokard " + org.fuin.sokar.app.SokarVersion.version());
        out.flush();
        return 0;
    }

    public static void main(final String[] args) {
        final Integer answered = answer(args, System.out, System.err);
        if (answered != null) {
            System.exit(answered);
        }
        final SokarContext context = SokarContext.real();
        final Path socket = context.paths().daemonSocket();
        // Started here and not in serving(): this is the one thing the daemon does that reaches
        // the network on its own, and nothing in a test should do that by existing.
        final java.time.Duration every =
                org.fuin.sokar.app.UpstreamWatch.interval(System::getenv);
        // Messages move because this goes and looks: the daemon binds no network interface, so
        // nothing can be pushed to it. Started here for the same reason as the upstream watch -
        // nothing in a test should walk a machine's mailboxes by existing.
        final java.time.Duration messages =
                org.fuin.sokar.app.MessageWatch.interval(System::getenv);
        final java.time.Duration following =
                org.fuin.sokar.app.ConfigurationWatch.interval(System::getenv);
        try (VarlinkServer server = serving(context, socket);
                org.fuin.sokar.app.UpstreamWatch upstream =
                        new org.fuin.sokar.app.UpstreamWatch(context, every);
                org.fuin.sokar.app.MessageWatch mail =
                        new org.fuin.sokar.app.MessageWatch(context, messages);
                org.fuin.sokar.app.ConfigurationWatch configuration =
                        new org.fuin.sokar.app.ConfigurationWatch(context, following)) {
            // try-with-resources does not run for a signal, and a signal is how a daemon normally
            // ends - systemctl stop, a kill, a terminal closing. Without this the socket file
            // outlived the process that bound it, and an interface met a name that answers
            // nothing: it connects, is refused, and cannot tell that from a daemon still starting.
            // SIGKILL still leaves it, and nothing can change that; the next start unlinks a
            // socket it has proved nobody is listening on.
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "sokard-shutdown"));
            upstream.start();
            mail.start();
            // Both: the watch answers at once where a message is already on this machine, and the
            // timer still goes and asks, because a watch can miss things and a transport that
            // fetches from elsewhere has to be asked rather than waited on.
            final boolean noticing = mail.startNotices();
            configuration.start();
            // An update replaces this binary and starts nothing: this notices, and has the account's own manager
            // restart the daemon on the new one.
            SelfReplacement.ofThisProcess(context.runner()).start();
            System.out.println("sokard listening on " + socket);
            System.out.println(messages.isZero() || messages.isNegative()
                    ? "not moving messages (" + org.fuin.sokar.app.MessageWatch.INTERVAL_VARIABLE
                            + "=0)"
                    : "moving every mailbox along every " + messages.toSeconds() + " seconds"
                            + (noticing ? ", and at once when one lands here"
                                    : " (this machine cannot watch directories)"));
            System.out.println(following.isZero() || following.isNegative()
                    ? "not following project repositories ("
                            + org.fuin.sokar.app.ConfigurationWatch.INTERVAL_VARIABLE + "=0)"
                    : "following each project's repository every " + following.toSeconds()
                            + " seconds");
            System.out.println(every.isZero() || every.isNegative()
                    ? "not measuring how far projects are behind upstream ("
                            + org.fuin.sokar.app.UpstreamWatch.INTERVAL_VARIABLE + "=0)"
                    : "measuring how far projects are behind upstream every "
                            + every.toMinutes() + " minutes");
            System.out.flush();
            server.run();
        } catch (RuntimeException ex) {
            System.err.println("sokard: " + ex.getMessage());
            System.err.flush();
            System.exit(70);
        }
    }
}
