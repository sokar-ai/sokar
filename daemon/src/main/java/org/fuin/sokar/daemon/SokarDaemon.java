package org.fuin.sokar.daemon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.runtime.ContainerName;
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

    /** Varlink interface this serves. */
    public static final String INTERFACE = "org.fuin.sokar.Tasks";

    /** Name of the socket inside Sokar's runtime directory. */
    public static final String SOCKET = "sokard.sock";

    /** How often a watch looks for a change. */
    static final java.time.Duration WATCH_INTERVAL = java.time.Duration.ofMillis(500);

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
        final VarlinkServer server = new VarlinkServer(socket, INTERFACE);
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

        return server;
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
                String.valueOf(task.helpers()));
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
        final Path socket = context.paths().xdg().runtime().resolve(SOCKET);
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
