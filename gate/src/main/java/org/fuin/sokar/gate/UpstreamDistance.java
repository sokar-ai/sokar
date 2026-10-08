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
     * @param alreadyUpstream Incoming refs whose commit the upstream already has, by short name.
     *        Somebody pushed that work by hand, so it is not waiting for anything - and the gate
     *        would otherwise list it as waiting for ever, because only {@code approve} deletes the
     *        ref and a hand push does not.
     */
    public record Distance(int behind, @Nullable Instant measured, Reason reason,
            @Nullable String detail, List<String> alreadyUpstream) {

        /**
         * Constructor for a measurement that looked at no refs.
         *
         * @param behind Commits the upstream has that the mirror does not.
         * @param measured When, or {@code null}.
         * @param reason Why the number is what it is.
         * @param detail What went wrong, or {@code null}.
         */
        public Distance(int behind, @Nullable Instant measured, Reason reason,
                @Nullable String detail) {
            this(behind, measured, reason, detail, List.of());
        }

        /** Defensive copy. */
        public Distance {
            alreadyUpstream = List.copyOf(alreadyUpstream);
        }

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
        return measure(runner, mirror, mode, GitCredentials.NONE);
    }

    /**
     * Measures one project's mirror against its upstream, reaching it with what the credentials lend.
     * <p>
     * The same lease the gate's own refresh takes: a private upstream over ssh answers only to its deploy key, and
     * measured without it every answer was "Permission denied".
     *
     * @param runner Runs git.
     * @param mirror The project's bare mirror.
     * @param mode What the project's security class allows.
     * @param credentials What reaches the upstream.
     * @return The distance, always an answer and never an exception.
     */
    public static Distance measure(CommandRunner runner, Path mirror, GateMode mode, GitCredentials credentials) {

        if (!mode.canForward()) {
            // An offline project's whole promise is that nothing leaves. Contacting the upstream
            // to see how far behind it is would break that promise to answer a question nobody
            // can act on: an offline project is never going to catch up.
            return new Distance(0, null, Reason.OFFLINE, null);
        }
        if (!Files.isDirectory(mirror.resolve("objects"))) {
            return Distance.neverChecked();
        }
        final String origin = remoteUrl(runner, mirror);
        if (origin == null) {
            // Created by 'git init --bare' rather than cloned. There is no upstream, so "behind"
            // has no meaning - as opposed to having a meaning nobody has measured yet.
            return new Distance(0, null, Reason.NO_UPSTREAM, null);
        }

        final CommandResult fetched;
        try (GitCredentials.Lease lease = credentials.forUrl(origin)) {
            final List<String> command = new java.util.ArrayList<>(List.of("git"));
            command.addAll(lease.arguments());
            command.addAll(List.of("--git-dir", mirror.toString(), "fetch", "--quiet", "origin"));
            fetched = runner.run(Command.of(command).withEnvironment(lease.environment()));
        } catch (RuntimeException ex) {
            return new Distance(0, null, Reason.FAILED, String.valueOf(ex.getMessage()));
        }
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
                    Reason.MEASURED, null, alreadyUpstream(runner, mirror));
        } catch (NumberFormatException ex) {
            return new Distance(0, null, Reason.FAILED,
                    "git answered '" + counted.standardOutput().strip() + "'");
        }
    }

    /**
     * Returns the incoming refs the upstream already has.
     * <p>
     * <strong>Why this is worth a second question during a fetch we are doing anyway.</strong>
     * {@code approve} pushes and then deletes the ref; a push made by hand does the first and not
     * the second. So the ref stays and the gate lists it as waiting for review permanently - a
     * queue that always has something in it is a queue nobody reads, and then the entry that
     * really is waiting does not stand out. Worse, {@code reject} afterwards records it as
     * discarded while the code is upstream.
     * <p>
     * <strong>What it can and cannot see.</strong> {@code FETCH_HEAD} is the upstream's default
     * branch, so this answers "the upstream's main line already has this". Work pushed by hand to
     * some other branch is not found, and reads as still waiting - which is the safe direction to
     * be wrong in: it leaves the entry in the queue rather than clearing something that is not
     * really there.
     *
     * @param runner Runs git.
     * @param mirror The bare mirror, with FETCH_HEAD from the fetch just made.
     * @return Short ref names, without the incoming namespace.
     */
    private static List<String> alreadyUpstream(CommandRunner runner, Path mirror) {
        final CommandResult listed = git(runner, mirror, "for-each-ref", "--format=%(refname)",
                GitGate.INCOMING);
        if (!listed.successful()) {
            return List.of();
        }
        final List<String> found = new java.util.ArrayList<>();
        listed.standardOutput().lines().map(String::strip)
                .filter(line -> line.startsWith(GitGate.INCOMING))
                .forEach(ref -> {
                    if (git(runner, mirror, "merge-base", "--is-ancestor", ref, "FETCH_HEAD")
                            .successful()) {
                        found.add(ref.substring(GitGate.INCOMING.length()));
                    }
                });
        return List.copyOf(found);
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
