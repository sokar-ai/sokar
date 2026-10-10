package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Where one project's file comes from.
 * <p>
 * <strong>A followed project's file is the one the machine verified.</strong> Following fetches a
 * repository, checks that its head commit is signed by a key pinned out of band, and only then
 * moves the clone. Reading anything else afterwards throws that away: a file in a working
 * directory has been checked by nobody, and the whole of following is that somebody checked.
 * <p>
 * <strong>This is the one place that answers it.</strong> A project's file could be in three
 * places - the directory somebody stood in, the one a create call chose, and the verified clone -
 * and three answers to one question is how a machine comes to run something nobody chose.
 */
public final class ProjectSource {

    private ProjectSource() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** Why a name could not be resolved, or that it was. */
    public enum Outcome {

        /** The name is a project this machine follows, and the file is its verified clone's. */
        FOLLOWED,

        /**
         * The name is a project this machine has run a task for, and the file is where that task
         * read it from. Nothing verified it.
         */
        RECORDED,

        /** This machine has no project of that name. */
        UNKNOWN,

        /** The project {@code default}, which every machine has and Sokar writes the file of itself. */
        BUILT_IN
    }

    /**
     * What a name resolved to.
     *
     * @param outcome Where it came from.
     * @param file The project file, or {@code null} when nothing was found.
     * @param commit The commit the file was applied at, or "" when nothing was applied.
     * @param unverified {@code true} for a project followed without an anchor, whose commits no
     *        signature was checked on - whoever can push to its repository decides what it says.
     */
    public record Found(Outcome outcome, @Nullable Path file, String commit, boolean unverified) {

        /**
         * Tells whether a task started from this would run against something checked.
         *
         * @return {@code true} only for a project followed with an anchor that has applied a commit.
         */
        public boolean verified() {
            return outcome == Outcome.FOLLOWED && !commit.isEmpty() && !unverified;
        }
    }

    /**
     * Resolves a project by name.
     * <p>
     * A followed project wins over a recorded one of the same name, and it is not close: one was
     * checked against a pinned key and the other was wherever a task last ran. A project that is
     * followed but has never applied a commit - refused, unreachable, nothing pinned - resolves to
     * nothing rather than to a stale file, because *"what is in force"* is then honestly nothing.
     *
     * @param context The machine.
     * @param name The project's name.
     * @return What was found.
     */
    public static Found resolve(final SokarContext context, final String name) {
        if (DefaultProject.is(name)) {
            // Before anything else: the name is reserved, so nothing followed or recorded may answer for it.
            return new Found(Outcome.BUILT_IN, new DefaultProject(context).file(), "", false);
        }
        try {
            final FollowedProjects.Followed followed =
                    new FollowedProjects(context.paths().projects().followed()).find(name);
            if (followed != null) {
                final Path file = context.paths().projects().followedClone(name).resolve("project.yml");
                return new Found(Outcome.FOLLOWED,
                        Files.isRegularFile(file) ? file : null, followed.commit(), followed.unverified());
            }
        } catch (final IOException ex) {
            // A follow record that cannot be read is not a reason to fall through to an unverified
            // file: that would answer the question the wrong way round, quietly.
            return new Found(Outcome.UNKNOWN, null, "", false);
        }
        final String recorded =
                new ProjectRegistry(context.paths().projects().projectRegistry()).all().get(name);
        if (recorded != null && Files.isRegularFile(Path.of(recorded))) {
            return new Found(Outcome.RECORDED, Path.of(recorded), "", false);
        }
        return new Found(Outcome.UNKNOWN, null, "", false);
    }

    /**
     * Resolves a project by name, refusing a name this machine does not have.
     *
     * @param context The machine.
     * @param name The project's name.
     * @return The file to read.
     * @throws org.fuin.sokar.core.project.ProjectException When there is no such project, or when
     *         it is followed and nothing of it is in force. The message names what there is.
     */
    public static Path require(final SokarContext context, final String name) {
        final Found found = resolve(context, name);
        if (found.outcome() == Outcome.FOLLOWED && found.file() == null) {
            throw new org.fuin.sokar.core.project.ProjectException("'" + name + "' is followed and"
                    + " nothing of it is in force: " + Shown.whyNotInForce(context, name) + ".");
        }
        if (found.file() == null) {
            final java.util.List<String> known = names(context);
            throw new org.fuin.sokar.core.project.ProjectException("no project '" + name
                    + "' here. " + (known.isEmpty()
                            ? "This machine follows none. A project comes to be here by following"
                                    + " its repository: sokar project follow <name> <url>"
                            : "This machine has: " + String.join(", ", known)));
        }
        return found.file();
    }

    /**
     * Returns the names this machine can resolve, followed ones first.
     * <p>
     * What a refusal prints. A person who mistypes a project name should be told which projects
     * this machine has, which is a question only the machine can answer.
     *
     * @param context The machine.
     * @return Names, without duplicates.
     */
    public static java.util.List<String> names(final SokarContext context) {
        final java.util.Set<String> all = new java.util.LinkedHashSet<>();
        all.add(DefaultProject.NAME);
        try {
            new FollowedProjects(context.paths().projects().followed()).all()
                    .forEach(one -> all.add(one.name()));
        } catch (final IOException ex) {
            // A listing of names is not the place to fail over the follow record.
            all.clear();
        }
        all.addAll(new ProjectRegistry(context.paths().projects().projectRegistry()).all().keySet());
        return java.util.List.copyOf(all);
    }
}
