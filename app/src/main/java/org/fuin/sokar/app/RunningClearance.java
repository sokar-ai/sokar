package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.fuin.sokar.wire.TaskProfile;

/**
 * Turns enforcement on or off on a task that is already running.
 * <p>
 * <strong>The state was chosen before anybody saw what the task would do.</strong> `Start` takes a
 * clearance mode and nothing changed it afterwards, so somebody watching a task ask about the same
 * host for the twentieth time had to stop it and start again to make it stop asking.
 * <p>
 * <strong>Deliberately not part of widening or narrowing.</strong> Those grant and withdraw names,
 * and that the firewall stays loaded is what they mean. Folding "stop asking about anything" into
 * them would make one method mean two unrelated things, and the quiet one would be the dangerous
 * one.
 * <p>
 * <strong>What it cannot undo.</strong> Turning enforcement off does not recall a connection that
 * was already refused - the packet was dropped and nothing retries it - and turning it back on
 * does not recall anything waved through while it was off. The firewall is loaded throughout, so
 * the change never opens anything by itself; what changes is whether a blocked connection produces
 * a question.
 */
public final class RunningClearance {

    /** The modes a task can be in. */
    public static final Set<String> MODES = Set.of("prompt", "allow", "deny", "off");

    /** What happened. */
    public enum Outcome {

        /** Enforcement is now in the mode that was asked for. */
        CHANGED,

        /** It was already in that mode. Nothing was restarted. */
        UNCHANGED,

        /** What would happen, having changed nothing. */
        PREVIEWED,

        /** No such task. */
        NO_SUCH_TASK,

        /** The task exists and is not up, so there is no watcher to start or stop. */
        NOT_RUNNING,

        /** A mode this daemon does not know. */
        UNKNOWN_MODE,

        /** The task was started by a Sokar that recorded no profile. */
        NOT_RECORDED,

        /** The change could not be made. */
        FAILED
    }

    /**
     * What a change did.
     *
     * @param outcome What happened.
     * @param was The mode before, or "" when nothing recorded one.
     * @param now The mode after, or "" when nothing changed.
     * @param detail Why it failed, or "".
     */
    public record Result(Outcome outcome, String was, String now, String detail) { }

    private RunningClearance() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Stops the watcher a task has, if it has one.
     *
     * @param context The machine.
     * @param container The task.
     * @return Whether one was stopped.
     */
    private static boolean stopWatcher(SokarContext context, String container) {
        final Path pidFile = context.paths().containerState(container).resolve("watcher.pid");
        if (!Files.isRegularFile(pidFile)) {
            return false;
        }
        try {
            // Verified first: the record has to still name the watcher. An id alone is reused,
            // and 'kill' does not ask whether the process it is given is the one that was meant.
            final java.util.Optional<ProcessHandle> watcher =
                    org.fuin.sokar.wire.HelperPid.verified(pidFile);
            Files.deleteIfExists(pidFile);
            if (watcher.isEmpty()) {
                return false;
            }
            // Through the runner rather than ProcessHandle: the watcher is a child of whatever
            // started the task, which is not this process when the daemon is asked.
            context.runner().run(org.fuin.sokar.core.process.Command.of(
                    List.of("kill", String.valueOf(watcher.get().pid()))));
            return true;
        } catch (java.io.IOException | RuntimeException ex) {
            return false;
        }
    }

    /**
     * Changes what a running task does about a blocked connection.
     *
     * @param context The machine.
     * @param container The task.
     * @param mode prompt, allow, deny or off.
     * @param dryRun Says what would happen and changes nothing.
     * @param out Where progress goes.
     * @return What happened.
     */
    public static Result set(SokarContext context, String container, String mode, boolean dryRun,
            PrintWriter out) {

        if (!MODES.contains(mode)) {
            return new Result(Outcome.UNKNOWN_MODE, "", "",
                    "expected one of " + String.join(", ", MODES.stream().sorted().toList()));
        }
        if (!org.fuin.sokar.runtime.ContainerName.isTask(container)) {
            return new Result(Outcome.NO_SUCH_TASK, "", "", container + " is not a Sokar task");
        }
        final Path state = context.paths().containerState(container);
        if (!Files.isDirectory(state)) {
            return new Result(Outcome.NO_SUCH_TASK, "", "", "nothing here knows " + container);
        }
        final TaskProfile profile = TaskProfile.readFrom(state);
        if (profile == null) {
            // Changing it without a profile would mean inventing the rest of one, which would
            // claim a mode and a start time nobody recorded.
            return new Result(Outcome.NOT_RECORDED, "", "",
                    "started by a Sokar that recorded no profile");
        }
        final String was = profile.clearance() == null ? "" : profile.clearance();
        if (was.equals(mode)) {
            return new Result(Outcome.UNCHANGED, was, was, "");
        }
        final org.fuin.sokar.wire.Sidecar sidecar = sidecarOf(context, container);
        if (sidecar == null) {
            // Without it the watcher cannot be told which project and task a prompt is about, and
            // a prompt that names neither is one nobody can act on.
            return new Result(Outcome.NOT_RECORDED, was, "",
                    "no sidecar recorded for " + container);
        }
        final java.util.Optional<Long> pid = context.tasks().containerPid(container);
        if (!"off".equals(mode) && pid.isEmpty()) {
            // Turning it off never needs the container: it is stopping a process. Turning it on
            // does, because the watcher enters the container's network namespace.
            return new Result(Outcome.NOT_RUNNING, was, "",
                    container + " reports no process, so no watcher can be started for it");
        }
        if (dryRun) {
            return new Result(Outcome.PREVIEWED, was, mode, "");
        }

        try {
            if (stopWatcher(context, container)) {
                out.println("stopped   the watcher that was running in "
                        + (was.isEmpty() ? "an unrecorded" : was) + " mode");
            }
            if ("off".equals(mode)) {
                out.println("clearance off - nothing is asked and nothing is refused; the ruleset"
                        + " stays loaded, so this opens nothing by itself");
                forgetWatcher(state);
            } else {
                final List<String> command = ClearanceWiring.watcherCommand(context,
                        sidecar.project(), taskOf(container, sidecar.project()), pid.get(),
                        container, mode);
                new ProcessBuilder(org.fuin.sokar.core.process.Scope.around("sokar " + container + " watcher", command)).redirectErrorStream(true)
                        .redirectOutput(state.resolve("clearance.log").toFile()).start();
                rememberWatcher(state, command);
                out.println("clearance " + mode + ", log at " + state.resolve("clearance.log"));
            }
            profile.withClearance(mode).writeTo(state);
            out.flush();
            new TaskState(context).save(container);
            return new Result(Outcome.CHANGED, was, mode, "");
        } catch (java.io.IOException | RuntimeException ex) {
            out.flush();
            return new Result(Outcome.FAILED, was, "", String.valueOf(ex.getMessage()));
        }
    }

    private static org.fuin.sokar.wire.@org.jspecify.annotations.Nullable Sidecar sidecarOf(SokarContext context, String container) {
        try {
            return org.fuin.sokar.wire.Sidecar.readFrom(
                    context.paths().containerState(container).resolve("sidecar.json"));
        } catch (java.io.IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * Returns the task's own name, given the project the container belongs to.
     * <p>
     * A container is {@code sokar-<project>-<task>-<run>} and a project name may contain hyphens,
     * so this is only answerable once the project is known - which is why it takes it rather than
     * splitting on hyphens and hoping.
     *
     * @param container The container.
     * @param project The project it belongs to.
     * @return The task name, or the container name when it does not have that shape.
     */
    static String taskOf(String container, String project) {
        final String prefix = org.fuin.sokar.runtime.ContainerName.PREFIX + project + "-";
        if (!container.startsWith(prefix)) {
            return container;
        }
        final String rest = container.substring(prefix.length());
        final int lastDash = rest.lastIndexOf('-');
        return lastDash <= 0 ? rest : rest.substring(0, lastDash);
    }

    /**
     * Replaces the watcher a resume would restart, so a resumed task comes back in the mode it was
     * changed to rather than the one it was started in.
     *
     * @param state The task's state directory.
     * @param command The new watcher command.
     * @throws java.io.IOException If the record cannot be written.
     */
    private static void rememberWatcher(Path state, List<String> command)
            throws java.io.IOException {
        final List<TaskHelpers.Helper> kept = new java.util.ArrayList<>(
                TaskHelpers.readFrom(state).helpers().stream()
                        .filter(helper -> !"watcher".equals(helper.name())).toList());
        kept.add(new TaskHelpers.Helper("watcher", command, java.util.Map.of(),
                TaskHelpers.AFTER));
        new TaskHelpers(List.copyOf(kept)).writeTo(state);
    }

    /**
     * Takes the watcher out of what a resume would restart.
     *
     * @param state The task's state directory.
     * @throws java.io.IOException If the record cannot be written.
     */
    private static void forgetWatcher(Path state) throws java.io.IOException {
        new TaskHelpers(TaskHelpers.readFrom(state).helpers().stream()
                .filter(helper -> !"watcher".equals(helper.name())).toList()).writeTo(state);
    }
}
