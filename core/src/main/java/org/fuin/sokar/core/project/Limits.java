package org.fuin.sokar.core.project;

import org.jspecify.annotations.Nullable;

/**
 * What one task container may consume.
 * <p>
 * An agent in YOLO mode runs commands nobody reviewed, so a runaway build is a normal outcome
 * rather than an attack. Memory is the one that takes the host down; podman already caps processes
 * at 2048, which is pinned here so the protection does not depend on a distribution default.
 *
 * @param memory Value for {@code --memory}, for example {@code 8g}, or {@code null} for no limit.
 * @param cpus Value for {@code --cpus}, for example {@code 2.0}, or {@code null} for no limit.
 * @param pids Value for {@code --pids-limit}.
 */
public record Limits(@Nullable String memory, @Nullable String cpus, int pids) {

    /** Memory a task gets unless the project says otherwise. */
    public static final String DEFAULT_MEMORY = "8g";

    /** Processes a task gets unless the project says otherwise, matching podman's own default. */
    public static final int DEFAULT_PIDS = 2048;

    /**
     * Returns the limits a project gets when it declares none.
     *
     * @return Defaults.
     */
    public static Limits defaults() {
        return new Limits(DEFAULT_MEMORY, null, DEFAULT_PIDS);
    }

    /**
     * Constructor.
     *
     * @param memory Memory limit, or {@code null}.
     * @param cpus CPU limit, or {@code null}.
     * @param pids Process limit.
     */
    public Limits {
        if (pids < 1) {
            throw new ProjectException("A process limit of " + pids + " leaves nothing to run");
        }
    }

    /**
     * Limits as a repository declares them: every key optional, and absent meaning the project's.
     * <p>
     * <strong>Why this is not a {@link Limits}.</strong> A {@code Limits} cannot say "nothing was
     * written here": its {@code pids} is an {@code int}, and its {@code null} memory already means
     * <em>no limit</em> rather than <em>unsaid</em>. A repository that named only {@code pids}
     * would then be indistinguishable from one that had also asked for no memory limit at all.
     * <p>
     * <strong>Absent falls back to the project's, never to the default.</strong> That is the trap
     * this type exists to close. A project that deliberately raised its memory and a repository
     * that says nothing about memory must end up with the project's value; falling back to
     * {@link #DEFAULT_MEMORY} would quietly undo the project's decision for that repository alone,
     * and nothing on screen would mention it.
     *
     * @param memory Value for {@code --memory}, {@code "none"} for no limit, or {@code null} when
     *        the repository said nothing.
     * @param cpus Value for {@code --cpus}, {@code "none"} for no limit, or {@code null} when the
     *        repository said nothing.
     * @param pids Process limit, or {@code null} when the repository said nothing.
     */
    public record Declared(@Nullable String memory, @Nullable String cpus, @Nullable Integer pids) {

        /** How a project or a repository opts out of a limit on purpose. */
        public static final String NONE = "none";

        /**
         * Constructor with checks.
         *
         * @param memory Memory limit, {@code "none"}, or {@code null}.
         * @param cpus CPU limit, {@code "none"}, or {@code null}.
         * @param pids Process limit, or {@code null}.
         */
        public Declared {
            if (pids != null && pids < 1) {
                throw new ProjectException("A process limit of " + pids + " leaves nothing to run");
            }
        }

        /**
         * Returns whether anything was declared at all.
         *
         * @return {@code true} when all three keys were absent.
         */
        public boolean isEmpty() {
            return memory == null && cpus == null && pids == null;
        }

        /**
         * Returns the limits in force, this one's keys over the given ones.
         * <p>
         * Key by key rather than all or nothing: a repository that names only {@code memory} keeps
         * the project's {@code cpus} and {@code pids}.
         *
         * @param base What the project declared, already resolved against the defaults.
         * @return The limits a task of this repository runs under.
         */
        public Limits over(final Limits base) {
            return new Limits(
                    memory == null ? base.memory() : NONE.equals(memory) ? null : memory,
                    cpus == null ? base.cpus() : NONE.equals(cpus) ? null : cpus,
                    pids == null ? base.pids() : pids);
        }

        /**
         * Returns a repository that declared no limits.
         *
         * @return All three absent.
         */
        public static Declared none() {
            return new Declared(null, null, null);
        }
    }
}
