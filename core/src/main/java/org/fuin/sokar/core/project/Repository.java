package org.fuin.sokar.core.project;

import org.jspecify.annotations.Nullable;

/**
 * One git repository a project's work happens in.
 * <p>
 * A project is a named unit of work over one or more repositories, and this is one of them. Its
 * own repository - where {@code project.yml}, the planning and the issues live - is a repository
 * like any other and is reached through {@link Project#ownRepository()}; the ones it names are
 * what its agents change.
 * <p>
 * <strong>A task works on exactly one.</strong> The repositories of a project are separate because
 * the work is separable - if it were not, it would be one repository - and a task that changed
 * three of them at once would have to be paid for at the gate, where <em>"this task's work is
 * waiting for review"</em> would have three answers and a person could approve a third of it.
 * Coordination between repositories happens between tasks, by message, with a record of what was
 * said.
 * <p>
 * Each repository keeps its own mirror, its own gate and its own review branch, so nothing about
 * review changes when a project names a second one.
 *
 * @param name What a person names when starting a task, and what the mirror directory is called.
 *        Held to the same shape as a project name, because it reaches a directory name.
 * @param upstream Where approved work goes, or {@code null}. Required by an
 *        {@link SecurityClass#ONLINE} project, where the agent's own remote is the upstream; for
 *        the other classes a repository without one is a repository whose work stays on this
 *        machine, which is a legitimate state rather than a broken one.
 * @param description What it is for, for people. May be empty.
 * @param egress What this repository's own tooling needs, <strong>added</strong> to the project's.
 *        Empty when it declared none. Additive because egress is a list of grants: adding is the
 *        only operation that makes sense, and a repository's declaration must never take away
 *        something the project granted - not least because allowing a blocked connection writes
 *        itself back into this block.
 * @param limits What this repository's work needs, <strong>replacing</strong> the project's key by
 *        key. Not additive, because a limit is one number with nothing to add it to, and two
 *        memory limits for one container is not something podman can be asked for. The rule
 *        follows the value.
 */
public record Repository(String name, @Nullable String upstream, String description,
        Egress egress, Limits.Declared limits) {

    /**
     * Constructor for a repository that declares neither egress nor limits.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is for.
     */
    public Repository(String name, @Nullable String upstream, String description) {
        this(name, upstream, description, Egress.none(), Limits.Declared.none());
    }

    /** A repository name: the same shape as a project name, for the same reason. */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    /**
     * Constructor without a description.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     */
    public Repository(String name, @Nullable String upstream) {
        this(name, upstream, "");
    }

    /**
     * Constructor with checks.
     *
     * @param name Repository name.
     * @param upstream Where approved work goes, or {@code null}.
     * @param description What it is for.
     */
    public Repository {
        egress = egress == null ? Egress.none() : egress;
        limits = limits == null ? Limits.Declared.none() : limits;
        if (name == null || !NAME.matcher(name).matches()) {
            // It becomes a directory under the mirrors, so it is refused here rather than where a
            // path turns out to hold a slash or a dot-dot.
            throw new ProjectException("Invalid repository name '" + name
                    + "', expected lower-case letters, digits and hyphens");
        }
        upstream = upstream == null || upstream.isBlank() ? null : upstream;
        description = description == null ? "" : description;
    }

    /**
     * Tells whether approved work from this repository has anywhere to go.
     *
     * @return {@code true} when an upstream was named.
     */
    public boolean hasUpstream() {
        return upstream != null;
    }

    /**
     * Tells whether this repository asks for anything of its own.
     *
     * @return {@code true} when it declared neither egress nor limits, and so runs exactly as the
     *         project says.
     */
    public boolean addsNothing() {
        return egress.isEmpty() && limits.isEmpty();
    }
}
