package org.fuin.sokar.release;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * What a pinned version looks like, how two compare, and what an update job does about them.
 */
public final class Versions {

    /**
     * Three numbers and nothing else.
     * <p>
     * A pointer that starts serving an error page or an empty body would otherwise travel into a pom
     * property and be found as a build failure two steps later.
     */
    public static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    private Versions() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** What an update job does about the pinned version and the one upstream offers. */
    public enum Verdict {

        /** Upstream is newer: move. */
        YES,

        /** They are the same: nothing to do. */
        NO,

        /** Upstream is older, which automation may not choose: a withdrawn release looks the same. */
        ROLLBACK;

        /**
         * The word a workflow reads.
         *
         * @return {@code yes}, {@code no} or {@code rollback}
         */
        public String word() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

    }

    /**
     * Whether a text is a version.
     *
     * @param text anything
     * @return true for three dot-separated numbers
     */
    public static boolean isVersion(String text) {
        return VERSION.matcher(text).matches();
    }

    /**
     * Compares two versions by their numbers.
     * <p>
     * As text 2.1.9 sorts after 2.1.10, and a gate that calls a real update a rollback gets switched off.
     *
     * @param left a version
     * @param right a version
     * @return negative, zero or positive as {@code left} is older, the same or newer
     */
    public static int compare(String left, String right) {
        return Arrays.compare(numbers(left), numbers(right));
    }

    /**
     * Decides what the job does.
     *
     * @param pinned what the module installs
     * @param upstream what upstream offers
     * @param named whether a person named {@code upstream} rather than a pointer - only then may it go backwards
     * @return the verdict
     */
    public static Verdict verdict(String pinned, String upstream, boolean named) {
        final int order = compare(upstream, pinned);
        if (order > 0) {
            return Verdict.YES;
        }
        if (order == 0) {
            return Verdict.NO;
        }
        return named ? Verdict.YES : Verdict.ROLLBACK;
    }

    /**
     * Whether the major version moved, which changes flags and configuration and nothing may decide alone.
     *
     * @param pinned what the module installs
     * @param upstream what upstream offers
     * @return true when the first number differs
     */
    public static boolean majorMoved(String pinned, String upstream) {
        return numbers(pinned)[0] != numbers(upstream)[0];
    }

    private static int[] numbers(String version) {
        if (!isVersion(version)) {
            throw new IllegalArgumentException("not a version: " + version);
        }
        return Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
    }

}
