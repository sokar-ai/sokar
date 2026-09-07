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

        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                SokarBinary.path(),
                "shield", "watch",
                // What the prompt shows. A container name carries a pid and identifies nothing
                // an operator recognizes; project and task are what they chose.
                "--project", project.name(),
                "--task", task,
                "--pid", String.valueOf(pid.get()),
                "--events", events.toString(),
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

        try {
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("clearance.log").toFile())
                    .start();
            // The container pid in here belongs to this run; a resume replaces it with the new one.
            recorder.record("watcher", command, java.util.Map.of(), TaskHelpers.AFTER);
            out.println("clearance " + mode + ", log at " + state.resolve("clearance.log"));
            out.flush();
        } catch (java.io.IOException ex) {
            // Losing the prompt costs recourse, not containment: the firewall keeps dropping
            // either way. Saying so beats failing a task that may not need it.
            err.println("sokar: could not start the clearance watcher: " + ex.getMessage());
            err.flush();
        }
    }
}
