package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.core.project.Repository;
import org.fuin.sokar.gate.LocalRepository;
import org.jspecify.annotations.Nullable;

/**
 * Says which project and repository a task started in a checkout belongs to, when no project is named.
 * <p>
 * <strong>A followed project that names the repository comes first</strong>: its settings are what somebody wrote for
 * it and signed. Otherwise the repository goes into {@code default}, where it stays until it is taken out there or a
 * followed project names it. Which repository it is, is the checkout's {@code origin}: that is where its approved work
 * goes, and the one thing a repository is known by on every machine.
 */
public final class WorkOrigin {

    /**
     * Where the work goes.
     *
     * @param project The project.
     * @param repository The repository in it.
     * @param said What to tell the person about how it was decided.
     */
    public record Choice(String project, String repository, String said) {
    }

    private WorkOrigin() {
    }

    /**
     * Decides from a checkout.
     *
     * @param context This machine.
     * @param directory Where the person stands.
     * @return The project and repository.
     * @throws ProjectException If the directory is in no checkout, the checkout has no {@code origin}, or the
     *         repository cannot be added to {@code default}.
     */
    public static Choice fromCheckout(final SokarContext context, final Path directory) {
        final Path top = LocalRepository.topLevel(context.runner(), directory);
        if (top == null) {
            throw new ProjectException("no project named, and " + directory.toAbsolutePath() + " is in no git"
                    + " checkout. Start in a checked-out repository, or name a project with -p.");
        }
        final CommandResult remote = context.runner().run(Command.of("git", "-C", top.toString(), "remote", "get-url",
                "origin"));
        final String given = remote.successful() ? remote.standardOutput().strip() : "";
        // A checkout cloned with a token names it in its origin. Neither kept nor printed: approved work over https
        // goes with the token stored once with 'sokar credentials', as for any https upstream.
        final String origin = RepositoryAddress.withoutCredential(given);
        final String removed = origin.equals(given) ? "" : "; its address carried a credential, which Sokar"
                + " leaves out of everything it keeps or shows";
        if (origin.isEmpty()) {
            throw new ProjectException(top + " has no 'origin', so approved work would have nowhere to go. Add one"
                    + " (git remote add origin <url>), or name a project with -p.");
        }
        final DefaultProject fallback = new DefaultProject(context);
        final DefaultProject.Entry kept = fallback.withUpstream(origin);
        final Choice followed = followedNaming(context, origin);
        if (followed != null) {
            return kept == null ? followed : new Choice(followed.project(), followed.repository(), followed.said()
                    + "; it is in '" + DefaultProject.NAME + "' too, and 'sokar project default remove "
                    + kept.name() + "' takes it out there");
        }
        try {
            final DefaultProject.Entry entry = fallback.add(origin, null, top.toString());
            return new Choice(DefaultProject.NAME, entry.name(), (kept == null ? "added to '" : "in '")
                    + DefaultProject.NAME + "' as '" + entry.name() + "': no followed project names " + origin
                    + removed);
        } catch (DefaultProject.Refused ex) {
            throw new ProjectException(String.valueOf(ex.getMessage()));
        }
    }

    /**
     * Returns the followed project and repository whose upstream is this one, or {@code null}.
     *
     * @param context This machine.
     * @param upstream A repository's address.
     * @return Where it belongs, or {@code null}.
     */
    public static @Nullable Choice followedNaming(final SokarContext context, final String upstream) {
        final java.util.List<FollowedProjects.Followed> followed;
        try {
            followed = new FollowedProjects(context.paths().projects().followed()).all();
        } catch (IOException ex) {
            return null;
        }
        for (final FollowedProjects.Followed one : followed) {
            final Path file = ProjectSource.resolve(context, one.name()).file();
            if (file == null) {
                continue;
            }
            final Project project;
            try {
                project = ProjectReader.read(file);
            } catch (ProjectException ex) {
                continue;
            }
            for (final Repository repository : project.allRepositories()) {
                if (RepositoryAddress.same(repository.upstream(), upstream)) {
                    return new Choice(project.name(), repository.name(), "'" + project.name() + "' names this"
                            + " repository as '" + repository.name() + "'");
                }
            }
        }
        return null;
    }
}
