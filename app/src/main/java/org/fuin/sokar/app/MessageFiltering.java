package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;

/**
 * Runs the filter that decides what a message may contain.
 * <p>
 * Sokar decides <em>who</em> may say something to <em>whom</em>; the filter decides <em>what</em> a
 * message may carry. It is a separate program on purpose, so that the answer to "was this checked?"
 * does not depend on which transport a message took.
 * <p>
 * <strong>Fail closed.</strong> A machine with no filter installed, or one whose filter cannot run,
 * moves nothing into a transport's queue. That is the difference between a door that is shut and a
 * door that is merely unattended, and it is why this reports what happened rather than swallowing
 * it.
 */
public final class MessageFiltering {

    /** The filter ran and refused nothing. */
    public static final int ALL_ACCEPTED = 0;

    /** The filter ran and refused something, which is an ordinary outcome and not a fault. */
    public static final int SOMETHING_REFUSED = 1;

    /**
     * What one pass did.
     *
     * @param ran Whether the filter was there and could be run at all.
     * @param exitCode Its exit code, or -1 when it did not run.
     * @param detail What to tell an operator, or "" when there is nothing to say.
     */
    public record Outcome(boolean ran, int exitCode, String detail) {
    }

    private final CommandRunner runner;

    private final Path filter;

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     * @param filter The filter's executable, or {@code null} when this machine has none.
     */
    public MessageFiltering(final CommandRunner runner, final Path filter) {
        this.runner = runner;
        this.filter = filter;
    }

    /**
     * Puts everything waiting in the mailbox through the filter.
     *
     * @param mailbox The task's mailbox.
     * @return What happened.
     */
    public Outcome run(final Mailbox mailbox) {
        if (filter == null) {
            return new Outcome(false, -1, "no message filter is installed, so nothing is sent");
        }
        final CommandResult result;
        try {
            // One flag: the filter derives the B14 layout from the mailbox root, so the two cannot
            // disagree about where anything is.
            //
            // '--blocking' is deliberately absent. A mailbox that has never been calibrated
            // reports what it would have refused instead of refusing it, because thresholds
            // derived from the properties of English prose are a guess until somebody has seen
            // this machine's own traffic.
            result = runner.run(Command.of(filter.toString(), "--mail", mailbox.root().toString()));
        } catch (final RuntimeException e) {
            return new Outcome(false, -1, "the message filter could not be run: " + e.getMessage());
        }
        if (result.exitCode() == ALL_ACCEPTED || result.exitCode() == SOMETHING_REFUSED) {
            return new Outcome(true, result.exitCode(), "");
        }
        return new Outcome(true, result.exitCode(),
                "the message filter stopped with " + result.exitCode() + ": "
                        + result.standardError().strip());
    }
}
