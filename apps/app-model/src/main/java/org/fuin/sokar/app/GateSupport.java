package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.core.project.Repository;
import org.fuin.sokar.gate.GateMode;
import org.fuin.sokar.gate.GitGate;
import org.jspecify.annotations.Nullable;

/**
 * Builds a gate for a project file, so the gate commands do not each repeat it.
 * <p>
 * Public because the daemon serves the gate as well. The CLI and the interface have to reach
 * identical behavior through the same calls, and a second way of resolving which mirror a
 * project's work is waiting in is how an approval comes to mean two things.
 */
public final class GateSupport {

    /**
     * Approves pending work as a merge signed by the person approving, in a scratch clone removed afterwards.
     *
     * @param gate The gate.
     * @param name The pending push.
     * @param branch The upstream branch.
     */
    public static void approveSigned(org.fuin.sokar.gate.GitGate gate, String name, String branch) {
        approveSigned(gate, name, branch, null);
    }

    /**
     * Merges the commit a person reviewed onto the upstream branch, signed with their own git's key, in a scratch
     * directory removed afterwards.
     *
     * @param gate The gate.
     * @param name The pending push.
     * @param branch The upstream branch.
     * @param reviewed The commit the person reviewed, or {@code null} for whatever the ref holds now.
     */
    public static void approveSigned(org.fuin.sokar.gate.GitGate gate, String name, String branch,
            @org.jspecify.annotations.Nullable String reviewed) {
        final java.nio.file.Path scratch;
        try {
            scratch = java.nio.file.Files.createTempDirectory("sokar-approve-");
        } catch (java.io.IOException ex) {
            throw new org.fuin.sokar.gate.GateException("Cannot make a scratch directory to merge in", ex);
        }
        try {
            // A directory of its own inside the scratch one: git init wants to make it.
            gate.approveSigned(name, branch, scratch.resolve("merge"), reviewed);
        } finally {
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(scratch)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (java.io.IOException ex) {
                // A scratch directory left behind in the temp directory harms nothing.
            }
        }
    }

    private GateSupport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a project file.
     *
     * @param projectFile The file.
     * @return The project.
     */
    public static Project project(Path projectFile) {
        return ProjectReader.read(projectFile);
    }

    /**
     * Reads a project by name, on a given machine.
     *
     * @param context The machine.
     * @param name The project's name.
     * @return The project.
     * @throws org.fuin.sokar.core.project.ProjectException If there is no such project.
     */
    public static Project byName(SokarContext context, String name) {
        return ProjectReader.read(ProjectSource.require(context, name));
    }

    static Path mirror(SokarContext context, Project project) {
        return mirror(context, project, project.ownRepository());
    }

    /**
     * Returns where one repository of a project keeps its mirror.
     * <p>
     * <strong>The project's own repository keeps the path it has always had.</strong> A mirror is
     * not a cache - it holds pushes nobody has reviewed yet - so moving one is not a rename but a
     * thing that can lose somebody's work. The repositories a project names are new, so they can
     * be put where they belong from the start, one directory down.
     *
     * @param context The machine.
     * @param project The project.
     * @param repository One of its repositories.
     * @return Path to the bare mirror, which need not exist yet.
     */
    static Path mirror(SokarContext context, Project project, Repository repository) {
        // The machine the command was given, never this process's own: a command that asked for the real account
        // here wrote mirrors there from every test that gave it another.
        final Path mirrors = context.paths().xdg().data().resolve("mirrors");
        return repository.name().equals(project.name())
                ? mirrors.resolve(project.name() + ".git")
                : mirrors.resolve(project.name()).resolve(repository.name() + ".git");
    }

    /**
     * Builds a project's gate.
     *
     * @param context The machine.
     * @param project The project.
     * @param upstream Where to forward approved work, or {@code null} for the project's own.
     * @return Gate.
     */
    public static GitGate gate(SokarContext context, Project project, @Nullable String upstream) {
        return gate(context, project, project.ownRepository(), upstream, null);
    }

    /**
     * Builds a gate, resolving where it forwards to and what it is first seeded from.
     *
     * @param context The machine.
     * @param project The project.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     * @param seed Repository to seed an empty mirror from, or {@code null}.
     * @return Gate.
     */
    public static GitGate gate(SokarContext context, Project project, @Nullable String upstream,
            @Nullable String seed) {
        return gate(context, project, project.ownRepository(), upstream, seed);
    }

    /**
     * Builds the gate of one repository of a project.
     * <p>
     * Each repository has its own mirror and therefore its own gate, its own review branch and its
     * own answer to <em>"what is waiting for review"</em>. That is the whole reason a task works on
     * exactly one of them.
     *
     * @param context The machine.
     * @param project The project.
     * @param repository Which of its repositories this gate serves.
     * @param upstream Value of {@code --upstream}, or {@code null} for the repository's own.
     * @param seed Repository to seed an empty mirror from, or {@code null}.
     * @return Gate.
     */
    public static GitGate gate(SokarContext context, Project project, Repository repository,
            @Nullable String upstream,
            @Nullable String seed) {
        final String forwardTo = upstream != null ? upstream : repository.upstream();
        // The mode follows the project's security class, so an offline project cannot be talked
        // into forwarding by a command-line flag. The class describes the box, and the box does
        // not change with which repository is open in it.
        // Attached here rather than at ten call sites: this is the factory that means "the gate
        // on this machine", and every command that has one may end up forwarding through it. A
        // repository that needs no credential - a local path, a public URL - takes nothing out of
        // the vault, so this costs nothing where it is not needed.
        return new GitGate(new ProcessCommandRunner(), mirror(context, project, repository),
                GateMode.of(project.securityClass()), forwardTo,
                forwardTo != null ? forwardTo : seed)
                .using(credentials(context, project.name(), repository.name()));
    }

    /**
     * Returns what reaches one repository's upstream: the gate's own refresh and forward, and the measurement of how
     * far its mirror is behind, all lend the same.
     *
     * @param context The machine.
     * @param project The project's name.
     * @param repository The repository's name.
     * @return The credentials, read from the vault for each command.
     */
    public static org.fuin.sokar.gate.GitCredentials credentials(SokarContext context, String project,
            String repository) {
        return new VaultGitCredentials(context, project + "-" + repository);
    }

    /**
     * Returns the key one repository's records are kept under.
     * <p>
     * <strong>One key for every per-repository record</strong> - the backups taken of a mirror and
     * the distance last measured from an upstream - because both answer a question about one
     * repository and both were keyed by project while a project was one repository.
     * <p>
     * <strong>The project's own repository keeps the project's own key</strong>, for the reason its
     * mirror keeps its own path: what was recorded before repositories existed was this, and a
     * rename would throw it away for nothing. The others are {@code <project>.<repository>}, which
     * cannot collide - both halves are lower-case letters, digits and hyphens, so the dot belongs
     * to neither.
     *
     * @param project Project name.
     * @param repository Repository name.
     * @return A key, safe as a file name.
     */
    public static String recordKey(String project, String repository) {
        return project.equals(repository) ? project : project + "." + repository;
    }

    /**
     * Resolves the repository a command was told to work on, falling back to the project's own.
     * <p>
     * <strong>The fallback is not Sokar picking between equals.</strong> These are the commands
     * whose subject is the project <em>file</em> - the gate, the shield, the talk commands - and
     * the project's own repository is the one that file belongs to. Starting a task is the other
     * case: there the repository is what the work is for, nothing points at one, and
     * {@link TaskLaunch} refuses a request that names none rather than reaching this.
     *
     * @param project The project.
     * @param named What was given, or {@code null} when nothing was.
     * @return The repository named, or the project's own when nothing was named.
     * @throws org.fuin.sokar.core.project.ProjectException If the project has no repository of
     *         that name. The message names what there is.
     */
    /**
     * Returns the repository a command about one task means: the one named, else the one that task worked on.
     * <p>
     * In {@code default} the project's own repository is an empty placeholder, so falling back to it told a person
     * approving a task's work "No upstream is configured for this project" - true of nothing they were looking at.
     * The task's own record says which repository it worked on, so naming the task is enough.
     *
     * @param context The machine.
     * @param project The project.
     * @param named The repository asked for, or {@code null}.
     * @param task The task the command is about.
     * @return The repository.
     */
    public static Repository repository(SokarContext context, Project project, @Nullable String named,
            String task) {
        if (named != null && !named.isBlank()) {
            return repository(project, named);
        }
        final String container = org.fuin.sokar.runtime.ContainerName.of(project, task);
        for (final TaskInventory.Task each : new TaskInventory(context).tasks()) {
            if (container.equals(each.name()) && each.repository() != null) {
                final Repository worked = project.repository(each.repository());
                if (worked != null) {
                    return worked;
                }
            }
        }
        // Never the own repository of a project that works elsewhere: no task's work is there to act on.
        return OwnRepository.unnamed(project);
    }

    public static Repository repository(Project project, @Nullable String named) {
        if (named == null || named.isBlank()) {
            return project.ownRepository();
        }
        final Repository found = project.repository(named);
        if (found == null) {
            throw new org.fuin.sokar.core.project.ProjectException("Project '" + project.name()
                    + "' has no repository '" + named + "'. It works in: " + OwnRepository.worksIn(project));
        }
        return found;
    }
}
