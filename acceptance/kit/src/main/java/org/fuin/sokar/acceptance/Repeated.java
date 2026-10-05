package org.fuin.sokar.acceptance;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.fuin.sokar.machines.Ssh;

/**
 * Runs a script again until it exits zero, for as long as a scenario allows, and stops early when an agent repeats a
 * failure.
 * <p>
 * For a wait whose answer is not on the terminal, such as an agent's reply held for a person: the script is the
 * check, the kit owns the waiting, so the same watch for a loop covers it as covers a wait on the terminal.
 */
final class Repeated {

    /** How long between two tries: each is a command on the machine, and each also looks for a loop. */
    static final Duration EVERY = Duration.ofSeconds(3);

    /** One try. */
    @FunctionalInterface
    interface Attempt {

        /**
         * Runs the script once.
         *
         * @return What it wrote and what it exited with.
         * @throws IOException If the machine cannot be reached.
         */
        Ssh.Output run() throws IOException;
    }

    private Repeated() {
    }

    /**
     * Tries until a try exits zero.
     *
     * @param named The script, as the scenario names it, for a failure to say.
     * @param attempt One try.
     * @param loops What is asked between tries whether an agent repeats a failure.
     * @param patience How long to allow.
     * @param every How long between two tries.
     * @param shown Filters what a failure prints.
     * @return The try that exited zero.
     * @throws IOException If the machine cannot be reached.
     */
    static Ssh.Output untilZero(String named, Attempt attempt, Terminal.Loops loops, Duration patience,
            Duration every, UnaryOperator<String> shown) throws IOException {
        final Instant began = Instant.now();
        final Instant deadline = began.plus(patience);
        loops.reset();
        while (true) {
            final Ssh.Output tried = attempt.run();
            if (tried.status() == 0) {
                return tried;
            }
            final Instant now = Instant.now();
            if (now.isAfter(deadline)) {
                throw new AssertionError("\"" + shown.apply(named) + "\" did not exit zero within "
                        + patience.toSeconds() + "s. Its last try said:\n" + shown.apply(tried.all()));
            }
            final Optional<String> loop = loops.check();
            if (loop.isPresent()) {
                throw new AssertionError("Stopped running \"" + shown.apply(named) + "\" after "
                        + Duration.between(began, now).toSeconds() + "s of " + patience.toSeconds() + "s. "
                        + shown.apply(loop.get()) + ". Its last try said:\n" + shown.apply(tried.all()));
            }
            try {
                Thread.sleep(every.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for \"" + shown.apply(named) + "\"", ex);
            }
        }
    }
}
