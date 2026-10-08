package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.jspecify.annotations.Nullable;

/**
 * Where Sokar keeps the files of a task's own files: its record, its state, its session, its clearance journal and its image's build context.
 * <p>
 * One class per area, beside {@link SokarPaths}, which keeps only the roots and what belongs to the machine as a
 * whole: a new location of an area is a change to its own file.
 *
 * @param paths The machine's roots.
 */
public record TaskPaths(SokarPaths paths) {

    /**
     * Returns the directory holding the runtime state of one container.
     *
     * @param container Container name.
     * @return State directory.
     */
    public Path containerState(String container) {
        // Under the runtime directory, so the kernel removes it when the session ends and no
        // stale sidecar can be picked up by a later run.
        return paths.xdg().runtime().resolve(container);
    }

    /**
     * Returns the file holding the session a task's agent was running.
     * <p>
     * Under the state directory, like the mailbox and for the same reason: it is the task's, and has to
     * survive a stop, a restart and a reboot, which the runtime directory does not. Deleted when the task is
     * removed.
     *
     * @param container Container name.
     * @return The file.
     */
    public Path sessionRecord(String container) {
        return paths.xdg().state().resolve("sessions").resolve(container);
    }

    /**
     * Returns the directory a task's durable state is saved in: what it is, how its helpers start, and the egress
     * it was given - the copies that bring it back after a reboot.
     * <p>
     * Under the state directory, which a reboot does not touch; the runtime directory, which it does, is still
     * where the task's files are used from. Deleted when the task is removed.
     *
     * @param container Container name.
     * @return The directory.
     */
    public Path taskRecord(String container) {
        return paths.xdg().state().resolve("tasks").resolve(container);
    }

    /**
     * Returns the directory holding the guide a task's agent is given, mounted into the task.
     * <p>
     * Under the state directory, like the mailbox: what a container mounts has to be there when it starts again after
     * a reboot. Deleted when the task is removed.
     *
     * @param container Container name.
     * @return The directory.
     */
    public Path guide(String container) {
        return paths.xdg().state().resolve("guides").resolve(container);
    }

    /**
     * Returns the file holding one task's clearance decisions.
     * <p>
     * Under the state directory, not beside the rest of the task's files. Everything else a task
     * writes is in the runtime directory, which the kernel clears at logout and
     * {@code task stop --remove} deletes - and a record of what an agent tried to reach that goes
     * when the task goes is not an audit record. Named by the container, and renamed when the task
     * is removed, so a decision made for one task is not silently in force for the next one of the
     * same name.
     *
     * @param container Container name.
     * @return The journal file.
     */
    public Path clearanceJournal(String container) {
        return paths.xdg().state().resolve("clearance").resolve(container + ".jsonl");
    }

    /**
     * Returns the build context directory for a project's image.
     *
     * @param project Project name.
     * @return Build context directory.
     */
    public Path buildContext(String project) {
        return paths.xdg().data().resolve("build").resolve(project);
    }
}
