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
public record Limits(@Nullable String memory, @Nullable String cpus, int pids, long handIn) {

    /** Memory a task gets unless the project says otherwise. */
    public static final String DEFAULT_MEMORY = "8g";

    /** Processes a task gets unless the project says otherwise, matching podman's own default. */
    public static final int DEFAULT_PIDS = 2048;

    /**
     * Bytes one file handed to a running task may have unless the project says otherwise: a build log or a
     * screenshot fits, a dataset is asked for on purpose.
     */
    public static final long DEFAULT_HAND_IN = 64L * 1024 * 1024;

    /** A size as the project file writes it: a whole number, optionally with k, m or g (powers of 1024). */
    private static final java.util.regex.Pattern SIZE = java.util.regex.Pattern.compile("(\\d+)([kKmMgG]?)");

    /**
     * Returns the limits a project gets when it declares none.
     *
     * @return Defaults.
     */
    public static Limits defaults() {
        return new Limits(DEFAULT_MEMORY, null, DEFAULT_PIDS, DEFAULT_HAND_IN);
    }

    /**
     * Constructor.
     *
     * @param memory Memory limit, or {@code null}.
     * @param cpus CPU limit, or {@code null}.
     * @param pids Process limit.
     * @param handIn Bytes one file handed to a running task may have.
     */
    public Limits {
        if (pids < 1) {
            throw new ProjectException("A process limit of " + pids + " leaves nothing to run");
        }
        if (handIn < 1) {
            throw new ProjectException("A hand-in limit of " + handIn + " bytes lets no file in");
        }
    }

    /**
     * Returns the bytes a size names.
     *
     * @param size What the project file says, such as {@code "64m"}.
     * @param key The key it was written under, for the refusal.
     * @return The bytes.
     * @throws ProjectException When it is not a size.
     */
    public static long bytes(final String size, final String key) {
        final java.util.regex.Matcher matcher = SIZE.matcher(size.strip());
        if (!matcher.matches()) {
            throw new ProjectException("'" + key + "' must be a size such as 64m, not '" + size + "'");
        }
        final long unit = switch (matcher.group(2).toLowerCase(java.util.Locale.ROOT)) {
            case "k" -> 1024L;
            case "m" -> 1024L * 1024;
            case "g" -> 1024L * 1024 * 1024;
            default -> 1L;
        };
        try {
            return Math.multiplyExact(Long.parseLong(matcher.group(1)), unit);
        } catch (NumberFormatException | ArithmeticException ex) {
            throw new ProjectException("'" + key + "' is too large: '" + size + "'");
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
    public record Declared(@Nullable String memory, @Nullable String cpus, @Nullable Integer pids,
            @Nullable Long handIn) {

        /** How a project or a repository opts out of a limit on purpose. */
        public static final String NONE = "none";

        /**
         * Constructor with checks.
         *
         * @param memory Memory limit, {@code "none"}, or {@code null}.
         * @param cpus CPU limit, {@code "none"}, or {@code null}.
         * @param pids Process limit, or {@code null}.
         * @param handIn Hand-in limit in bytes, or {@code null}.
         */
        public Declared {
            if (pids != null && pids < 1) {
                throw new ProjectException("A process limit of " + pids + " leaves nothing to run");
            }
            if (handIn != null && handIn < 1) {
                throw new ProjectException("A hand-in limit of " + handIn + " bytes lets no file in");
            }
        }

        /**
         * Returns whether anything was declared at all.
         *
         * @return {@code true} when every key was absent.
         */
        public boolean isEmpty() {
            return memory == null && cpus == null && pids == null && handIn == null;
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
                    pids == null ? base.pids() : pids,
                    handIn == null ? base.handIn() : handIn);
        }

        /**
         * Returns a repository that declared no limits.
         *
         * @return Every key absent.
         */
        public static Declared none() {
            return new Declared(null, null, null, null);
        }
    }
}
