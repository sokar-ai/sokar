package org.fuin.sokar.app;

import java.io.PrintWriter;
import org.fuin.sokar.core.project.Project;

/**
 * The watcher that asks about a connection the firewall dropped.
 * <p>
 * Split out of {@code TaskRunCommand} with the other wirings. It is one method, and it is here
 * rather than beside the credential broker because it belongs to a different question: the broker
 * decides what a task may *hold*, this decides what a task may *reach* once it is already running.
 */
final class ClearanceWiring {

    private final SokarContext context;

    private final CredentialWiring.Recorder recorder;

    private final String task;

    private final String mode;

    /**
     * Constructor with what the run decided.
     *
     * @param context Where podman and the paths come from.
     * @param recorder Where the watcher is recorded, so a resumed task starts it again.
     * @param task Task name, shown in the prompt.
     * @param mode Value of {@code --clearance}: ask, allow, deny or off.
     */
    ClearanceWiring(SokarContext context, CredentialWiring.Recorder recorder, String task,
            String mode) {
        this.context = context;
        this.recorder = recorder;
        this.task = task;
        this.mode = mode;
    }

    /**
     * Starts the clearance watcher for this container, following the events the reader hook is
     * already writing.
     * <p>
     * Detached on purpose. This process either replaces itself with a shell or returns when the
     * agent finishes, and in both cases the watcher has to outlive it - a blocked connection
     * during an interactive session needs a prompt just as much as one during a headless run. The
     * watcher writes a pid file, and the supervisor hook reaps it at poststop.
     */
    /**
     * Returns the command that runs a watcher for one task.
     * <p>
     * One place, because enforcement can also be changed on a task that is already running - and a
     * watcher started by that path with different arguments would behave differently from one
     * started at the beginning, in a way nothing would report.
     *
     * @param context The machine.
     * @param project Project name, which is what a prompt shows.
     * @param task Task name, likewise.
     * @param pid The container's process, whose network namespace the watcher enters.
     * @param container The container.
     * @param mode prompt, allow or deny. Never "off" - that is the absence of a watcher.
     * @return Command and arguments.
     */
    static java.util.List<String> watcherCommand(SokarContext context, String project, String task,
            long pid, String container, String mode) {

        final java.nio.file.Path state = context.paths().containerState(container);
        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                SokarBinary.path(),
                "shield", "watch",
                // What the prompt shows. A container name carries a pid and identifies nothing
                // an operator recognizes; project and task are what they chose.
                "--project", project,
                "--task", task,
                "--pid", String.valueOf(pid),
                "--events", state.resolve(org.fuin.sokar.wire.ReaderEvents.FILE).toString(),
                "--socket", state.resolve("clearance.sock").toString(),
                // Outside the state directory on purpose: this one has to survive the task it
                // records, and a resumed watcher reads it back so nothing is asked twice.
                "--journal", context.paths().clearanceJournal(container).toString(),
                "--pid-file", state.resolve("watcher.pid").toString()));
        switch (mode) {
            case "allow" -> command.add("--allow-all");
            case "deny" -> command.add("--deny-all");
            default -> { }
        }
        return java.util.List.copyOf(command);
    }

    void startClearance(TaskRunner runner, Project project, String container,
            PrintWriter out, PrintWriter err) {

        if ("off".equals(mode)) {
            return;
        }

        // The watcher edits the container's live nftables set, which means entering its network
        // namespace, which means knowing its pid. Starting one without it produces a watcher that
        // reaches a verdict and cannot act on it - which reads exactly like a working watcher.
        final java.util.Optional<Long> pid = runner.containerPid(container);
        if (pid.isEmpty()) {
            err.println("sokar: the container reports no process, so no clearance watcher"
                    + " was started; blocked connections will stay blocked");
            err.flush();
            return;
        }

        final java.nio.file.Path state = context.paths().containerState(container);
        final java.nio.file.Path events =
                state.resolve(org.fuin.sokar.wire.ReaderEvents.FILE);

        final java.util.List<String> command = watcherCommand(context, project.name(), task,
                pid.get(), container, mode);
        try {
            // Left by an earlier run of the same task: waiting for it to appear would return at once.
            java.nio.file.Files.deleteIfExists(state.resolve("clearance.sock"));
        } catch (java.io.IOException ex) {
            // The watcher unlinks it itself before binding; only the wait below is less exact.
        }

        try {
            final Process watcher = new ProcessBuilder(
                    org.fuin.sokar.core.process.Scope.around("sokar " + container + " watcher", command))
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("clearance.log").toFile())
                    .start();
            // The container pid in here belongs to this run; a resume replaces it with the new one.
            recorder.record("watcher", command, java.util.Map.of(), TaskHelpers.AFTER);
            // Returned only once it listens: it follows the event log from where it starts, so a
            // connection the agent makes before then is dropped and never seen - measured, when the
            // watcher's start began to go through the user manager and took a round trip longer.
            if (!listening(state.resolve("clearance.sock"), WATCHER_PATIENCE, watcher::isAlive)) {
                err.println("sokar: the clearance watcher is not listening yet; see " + state.resolve("clearance.log"));
                err.flush();
            }
            out.println("clearance " + mode + ", log at " + state.resolve("clearance.log"));
            out.flush();
        } catch (java.io.IOException ex) {
            // Losing the prompt costs recourse, not containment: the firewall keeps dropping
            // either way. Saying so beats failing a task that may not need it.
            err.println("sokar: could not start the clearance watcher: " + ex.getMessage());
            err.flush();
        }
    }

    /** How long a start waits for its watcher to listen. */
    static final java.time.Duration WATCHER_PATIENCE = java.time.Duration.ofSeconds(10);

    /**
     * Waits until a socket is there, the process that would make it has exited, or the patience runs out.
     *
     * @param socket The socket.
     * @param patience How long to wait.
     * @param alive Whether the process that makes it still runs.
     * @return {@code true} once it exists.
     */
    static boolean listening(java.nio.file.Path socket, java.time.Duration patience,
            java.util.function.BooleanSupplier alive) {
        final long until = System.nanoTime() + patience.toNanos();
        while (!java.nio.file.Files.exists(socket)) {
            if (System.nanoTime() > until || !alive.getAsBoolean()) {
                return false;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
