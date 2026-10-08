package org.fuin.sokar.app;

import java.io.PrintWriter;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.Repository;
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

    /** Which of the project's repositories this task works on, or {@code null} for its own. */
    private final @Nullable String repository;

    /**
     * Constructor with what the run decided.
     *
     * @param context Where podman and the paths come from.
     * @param task Task name, which becomes the ref the agent pushes to.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     */
    WorkspaceSetup(SokarContext context, String task, @Nullable String upstream) {
        this(context, task, upstream, null);
    }

    /**
     * Constructor naming the repository.
     *
     * @param context Where podman and the paths come from.
     * @param task Task name, which becomes the ref the agent pushes to.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     * @param repository Which repository of the project, or {@code null} for its own.
     */
    WorkspaceSetup(SokarContext context, String task, @Nullable String upstream,
            @Nullable String repository) {
        this.context = context;
        this.task = task;
        this.upstream = upstream;
        this.repository = repository;
    }

    /**
     * Opens the git gate for this task, unless the caller asked for none.
     *
     * @param wanted Whether this run has a gate at all: a dry run and {@code --no-gate}
     *        both mean no workspace, and neither is this class's decision to read.
     * @return The workspace, or {@code null} when running without a gate.
     */
    @Nullable TaskWorkspace openWorkspace(Project project, boolean wanted, PrintWriter out,
            PrintWriter err) {
        if (!wanted) {
            return null;
        }
        try {
            final Repository chosen = GateSupport.repository(project, repository);
            if (project.securityClass() == org.fuin.sokar.core.project.SecurityClass.ONLINE
                    && chosen.upstream() == null && upstream == null) {
                // Online passes every push on at once, which needs somewhere to pass it to. Its gate is the same as a
                // guarded task's: the agent's remote is the gate, and only the host reaches the upstream.
                err.println("sokar: an online project needs an upstream for '" + chosen.name()
                        + "': its gate passes every push on to it");
                err.flush();
                return null;
            }
            final TaskWorkspace opened = TaskWorkspace.gated(chosen,
                    GateSupport.gate(context, project, chosen, upstream, seed(project, chosen, out)),
                    TaskWorkspace.containerVisibleHost());
            if (!opened.refreshed().isEmpty()) {
                err.println("sokar: " + opened.refreshed());
                err.flush();
            }
            return opened;
        } catch (RuntimeException ex) {
            // A task with no workspace is still a useful task - a shell in a hardened box - so
            // this reports and continues rather than refusing to start.
            err.println("sokar: no git gate for this task: " + ex.getMessage());
            final String said = String.valueOf(ex.getMessage());
            if (HostKeys.refusedTheHost(said)) {
                // Answered as the question it is, as a follow answers it: git's own words name no way on, and a person
                // new to Sokar met them on the first task in 'default' (2026-10-01).
                final String host = GitCredentialNames.hostOf(String.valueOf(GateSupport.repository(project, repository)
                        .upstream()));
                err.println("sokar: " + (HostKeys.changed(said)
                        ? host + " answered with a different key from the one this machine remembers - nothing was"
                                + " fetched; find out why before trusting it again"
                        : "this machine has never met " + host + ", so it stopped rather than deciding for you. See"
                                + " what it offers with 'sokar credentials trust-host " + host + "', confirm one"
                                + " against what its owner publishes, and start again."));
            }
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
     * <strong>Only for the project's own repository.</strong> The checkout somebody is standing in
     * is the project's own - that is where {@code project.yml} is - so seeding a repository the
     * project merely names from it would fill that repository's mirror with somebody else's
     * history. A named repository with no upstream simply starts empty, which is what it is.
     *
     * @param project The project.
     * @param chosen The repository this task works on.
     * @param out Where to report.
     * @return Path of the work tree, or {@code null} when there is none.
     */
    @org.jspecify.annotations.Nullable
    private String seed(Project project, Repository chosen, PrintWriter out) {
        if (!chosen.name().equals(project.name())) {
            return null;
        }
        if (upstream != null || chosen.hasUpstream()
                || java.nio.file.Files.isDirectory(
                        GateSupport.mirror(context, project, chosen).resolve("objects"))) {
            // A mirror that exists is never re-seeded, so saying it would be seeded is a lie.
            return null;
        }
        // A followed project's own repository is seeded from the clone this machine verified.
        // That clone IS its history, and nothing should fetch it twice from the forge - nor,
        // now that a project is named rather than pointed at, can "the checkout you are standing
        // in" mean anything: the command runs wherever somebody happened to be.
        //
        // Found by a rented machine: '/workspace' came up empty, because the old rule looked for
        // a git repository in the working directory and there was none.
        final java.nio.file.Path clone = context.paths().projects().followedClone(project.name());
        if (java.nio.file.Files.isDirectory(clone.resolve(".git"))) {
            out.println("seed      " + clone + " (the followed clone)");
            return clone.toString();
        }
        final java.nio.file.Path local = ownCheckout(project.name(), java.nio.file.Path.of("."));
        if (local == null) {
            return null;
        }
        out.println("seed      " + local + " (committed history only)");
        return local.toString();
    }

    /**
     * Returns the checkout a command runs in when it is the project's own repository, the one its project file is in.
     * <p>
     * Any checkout it ran in was taken: through the daemon that is its home, and a home kept in git gave the agent's
     * mirror its whole history.
     *
     * @param project The project's name.
     * @param here Where the command runs.
     * @return The checkout's top level, or {@code null} when it is not the project's own.
     */
    static java.nio.file.@org.jspecify.annotations.Nullable Path ownCheckout(String project, java.nio.file.Path here) {
        final java.nio.file.Path local =
                org.fuin.sokar.gate.LocalRepository.topLevel(new org.fuin.sokar.core.process.ProcessCommandRunner(), here);
        if (local == null) {
            return null;
        }
        try {
            return org.fuin.sokar.core.project.ProjectReader.read(local.resolve("project.yml")).name().equals(project)
                    ? local : null;
        } catch (final RuntimeException ex) {
            return null;
        }
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
    void prepareWorkspace(TaskRunner runner, TaskWorkspace workspace,
            String container, java.util.Map<String, String> environment, PrintWriter out, PrintWriter err) {

        final java.nio.file.Path log =
                context.paths().tasks().containerState(container).resolve("workspace.log");
        final int code = runner.execute(container, environment, workspace.cloneCommand(),
                log, java.time.Duration.ofMinutes(5));
        String problem = null;
        String said = "";
        try {
            said = java.nio.file.Files.isRegularFile(log) ? java.nio.file.Files.readString(log) : "";
            problem = TaskWorkspace.fetchProblem(said);
        } catch (java.io.IOException ex) {
            // Unread, nothing is said about the fetch; the outcome below still is.
        }
        if (problem != null) {
            err.println("sokar: the workspace starts without its remote: " + problem);
            err.flush();
        }
        if (code == 0) {
            out.println("workspace " + TaskWorkspace.MOUNT + " ready");
        } else {
            err.println("sokar: could not prepare the workspace, see " + log);
            err.flush();
        }
        out.flush();
    }
}
