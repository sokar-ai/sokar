package org.fuin.sokar.gate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * How far a project's mirror has fallen behind the upstream it was cloned from.
 * <p>
 * <strong>This reaches the network, so nothing reads it on the way to answering a question.</strong>
 * It is measured on a schedule and the answer is reported with the time it was taken, because a
 * number somebody has to wait for is worse than a number with an age on it: an interface can render
 * "3 behind, as of 20 minutes ago" and cannot render a stall nobody on screen can explain.
 * <p>
 * <strong>It moves no ref.</strong> Measured rather than assumed: {@code git clone --bare} sets a
 * remote URL and no fetch refspec, so {@code git fetch origin} in the mirror writes only
 * {@code FETCH_HEAD} and leaves {@code refs/heads/*} exactly where they were. The gate's own view
 * of what has been reviewed therefore cannot be disturbed by measuring. It does add objects to the
 * mirror, which is what makes counting possible at all.
 */
public final class UpstreamDistance {

    /** Why the number is what it is - or why there is none. */
    public enum Reason {

        /** The number was measured, and {@code measured} says when. */
        MEASURED,

        /** Nothing has looked yet. Not the same as being up to date. */
        NEVER_CHECKED,

        /** The mirror was created empty, so there is nothing to be behind. */
        NO_UPSTREAM,

        /** The project reaches nothing by class, so asking would break the promise the class makes. */
        OFFLINE,

        /** Looking failed. {@code detail} says what git said. */
        FAILED
    }

    /**
     * How far behind a project is, and how much that answer is worth.
     *
     * @param behind Commits the upstream has that the mirror does not. Meaningless unless the
     *        reason is {@link Reason#MEASURED}, and zero there means up to date.
     * @param measured When it was measured, or {@code null} when it never was.
     * @param reason Why the number is what it is.
     * @param detail What went wrong, for {@link Reason#FAILED}. Never shown as a number.
     */
    public record Distance(int behind, @Nullable Instant measured, Reason reason,
            @Nullable String detail) {

        /**
         * Returns the answer for a project nothing has looked at.
         *
         * @return A distance with no number and no time.
         */
        public static Distance neverChecked() {
            return new Distance(0, null, Reason.NEVER_CHECKED, null);
        }
    }

    private UpstreamDistance() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Measures one project's mirror against its upstream.
     *
     * @param runner Runs git.
     * @param mirror The project's bare mirror.
     * @param mode What the project's security class allows.
     * @return The distance, always an answer and never an exception: this runs on a timer, and a
     *         project that cannot be reached must not stop the others being measured.
     */
    public static Distance measure(CommandRunner runner, Path mirror, GateMode mode) {

        if (!mode.canForward()) {
            // An offline project's whole promise is that nothing leaves. Contacting the upstream
            // to see how far behind it is would break that promise to answer a question nobody
            // can act on: an offline project is never going to catch up.
            return new Distance(0, null, Reason.OFFLINE, null);
        }
        if (!Files.isDirectory(mirror.resolve("objects"))) {
            return Distance.neverChecked();
        }
        if (remoteUrl(runner, mirror) == null) {
            // Created by 'git init --bare' rather than cloned. There is no upstream, so "behind"
            // has no meaning - as opposed to having a meaning nobody has measured yet.
            return new Distance(0, null, Reason.NO_UPSTREAM, null);
        }

        final CommandResult fetched = git(runner, mirror, "fetch", "--quiet", "origin");
        if (!fetched.successful()) {
            return new Distance(0, null, Reason.FAILED, firstLine(fetched));
        }
        // HEAD rather than a branch name: the mirror's own default is whatever it was cloned with,
        // and naming 'main' would answer 0 for every repository that calls it something else.
        final CommandResult counted =
                git(runner, mirror, "rev-list", "--count", "HEAD..FETCH_HEAD");
        if (!counted.successful()) {
            return new Distance(0, null, Reason.FAILED, firstLine(counted));
        }
        try {
            return new Distance(Integer.parseInt(counted.standardOutput().strip()), Instant.now(),
                    Reason.MEASURED, null);
        } catch (NumberFormatException ex) {
            return new Distance(0, null, Reason.FAILED,
                    "git answered '" + counted.standardOutput().strip() + "'");
        }
    }

    @Nullable
    private static String remoteUrl(CommandRunner runner, Path mirror) {
        final CommandResult result = git(runner, mirror, "config", "--get", "remote.origin.url");
        if (!result.successful()) {
            return null;
        }
        final String url = result.standardOutput().strip();
        return url.isEmpty() ? null : url;
    }

    private static CommandResult git(CommandRunner runner, Path mirror, String... arguments) {
        final List<String> command = new java.util.ArrayList<>(
                List.of("git", "--git-dir", mirror.toString()));
        command.addAll(List.of(arguments));
        return runner.run(Command.of(command));
    }

    private static String firstLine(CommandResult result) {
        final String said = (result.standardOutput() + " " + result.standardError()).strip();
        return said.lines().findFirst().orElse("git failed and said nothing").strip();
    }
}
