package org.fuin.sokar.app;

import java.io.PrintWriter;
import org.fuin.sokar.core.project.Project;
import org.jspecify.annotations.Nullable;

/**
 * The repository the agent works in, and where it came from.
 * <p>
 * Split out of {@code TaskRunCommand} with the other wirings. A workspace is a clone the container
 * makes from Sokar's own mirror, never a directory from the host: what the agent writes stays
 * inside the container until it is pushed through the gate, which is the property the whole review
 * step rests on.
 */
final class WorkspaceSetup {

    private final SokarContext context;

    private final String task;

    private final @Nullable String upstream;

    /**
     * Constructor with what the run decided.
     *
     * @param context Where podman and the paths come from.
     * @param task Task name, which becomes the ref the agent pushes to.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     */
    WorkspaceSetup(SokarContext context, String task, @Nullable String upstream) {
        this.context = context;
        this.task = task;
        this.upstream = upstream;
    }

    /**
     * Opens the git gate for this task, unless the caller asked for none.
     *
     * @param wanted Whether this run has a gate at all: a dry run and {@code --no-gate}
     *        both mean no workspace, and neither is this class's decision to read.
     * @return The workspace, or {@code null} when running without a gate.
     */
    TaskWorkspace openWorkspace(Project project, boolean wanted, PrintWriter out,
            PrintWriter err) {
        if (!wanted) {
            return null;
        }
        try {
            if (project.securityClass() == org.fuin.sokar.core.project.SecurityClass.ONLINE) {
                // Online takes the gate out of the path entirely: the agent's remote IS the
                // upstream. Nothing is reviewed, which is what the class is for and why a project
                // has to opt into it rather than a task asking for it.
                return TaskWorkspace.direct(project.upstream());
            }
            return TaskWorkspace.gated(
                    GateSupport.gate(project, upstream, seed(project, out)),
                    TaskWorkspace.containerVisibleHost());
        } catch (RuntimeException ex) {
            // A task with no workspace is still a useful task - a shell in a hardened box - so
            // this reports and continues rather than refusing to start.
            err.println("sokar: no git gate for this task: " + ex.getMessage());
            err.flush();
            return null;
        }
    }

    /**
     * Returns the repository an empty mirror should be seeded from.
     * <p>
     * Only reached when neither {@code --upstream} nor the project names one. Standing in a
     * checkout is taken as meaning that checkout, so the common case needs no flag. It is printed
     * rather than assumed silently, and only committed history is copied - a bare clone has no
     * working tree.
     *
     * @param project The project.
     * @param out Where to report.
     * @return Path of the work tree, or {@code null} when there is none.
     */
    @org.jspecify.annotations.Nullable
    private String seed(Project project, PrintWriter out) {
        if (upstream != null || project.upstream() != null
                || java.nio.file.Files.isDirectory(GateSupport.mirror(project).resolve("objects"))) {
            // A mirror that exists is never re-seeded, so saying it would be seeded is a lie.
            return null;
        }
        final java.nio.file.Path local = org.fuin.sokar.gate.LocalRepository.topLevel(
                new org.fuin.sokar.core.process.ProcessCommandRunner(),
                java.nio.file.Path.of("."));
        if (local == null) {
            return null;
        }
        out.println("seed      " + local + " (committed history only)");
        return local.toString();
    }

    /**
     * Clones the mirror into the container's workspace.
     *
     * @param runner Runs it inside the container.
     * @param workspace What to clone and where from.
     * @param container Container name.
     * @param environment What the clone needs - the gate's URL and its token.
     * @param out Where progress is reported.
     * @param err Where a failure is reported.
     */
    void prepareWorkspace(TaskRunner runner, TaskWorkspace workspace, String container,
            java.util.Map<String, String> environment, PrintWriter out, PrintWriter err) {

        final java.nio.file.Path log =
                context.paths().containerState(container).resolve("workspace.log");
        final int code = runner.execute(container, environment, workspace.cloneCommand(),
                log, java.time.Duration.ofMinutes(5));
        if (code == 0) {
            out.println("workspace " + TaskWorkspace.MOUNT + " ready");
        } else {
            err.println("sokar: could not prepare the workspace, see " + log);
            err.flush();
        }
        out.flush();
    }
}
