package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.project.Mail;

/**
 * Puts a message from an {@code external} peer through the filter before any agent sees it.
 * <p>
 * Trust is a property of the peer. A <strong>vouched</strong> peer is a machine that runs this same
 * filter on the way out, and checking its messages again would be doing twice what was already done.
 * An <strong>external</strong> peer is anybody else, and "anybody else" is exactly the case the
 * filter exists for - so the message is checked here, before it reaches the agent, with the same
 * program and the same rules that check what leaves.
 * <p>
 * <strong>Fail closed, again.</strong> No filter installed means a message from an external peer is
 * held, not delivered. A machine that cannot check is not a machine that may skip checking.
 * <p>
 * <strong>A refusal is not answered to the sender.</strong> What the filter refused came from
 * outside; replying with what was wrong with it would tell somebody unvouched exactly what this
 * machine looks for. It is held together with the filter's own answer, which is redacted by
 * construction and is the only record of why - the operator deciding on a release needs it.
 * <p>
 * <strong>And here the filter is asked to block, which is the one place it is.</strong> On the way
 * out it runs in reporting mode, because thresholds fitted to nobody's traffic would refuse an
 * agent's ordinary work before anybody had seen what it normally says. That argument is about the
 * sender's own traffic and does not reach this direction: here the sender is the peer this machine
 * does not vouch for. Without it the check would be worthless rather than lenient - the filter's
 * agent measured that in reporting mode only a malformed envelope moves a message to
 * {@code rejected/}, so text full of base64 or a message hidden in zero-width characters lands in
 * {@code accepted/} with the finding in a receipt nobody here reads.
 */
public final class InboundCheck {

    private final CommandRunner runner;

    private final Path filter;

    private final Mail mail;

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     * @param filter The filter's executable, or {@code null} when this machine has none.
     * @param mail The project's peers, which is where trust is written down.
     */
    public InboundCheck(final CommandRunner runner, final @org.jspecify.annotations.Nullable Path filter,
            final Mail mail) {
        this.runner = runner;
        this.filter = filter;
        this.mail = mail;
    }

    /**
     * Checks one message, if its peer is one whose messages are checked.
     *
     * @param mailbox The task's mailbox.
     * @param message The message, still in {@code inbound}.
     * @param peer The peer whose key signed it.
     * @return An empty string when it may be delivered, otherwise why it may not.
     * @throws IOException Reading or moving failed.
     */
    public String refuse(final Mailbox mailbox, final Path message, final String peer)
            throws IOException {
        final Mail.Peer known = mail.peer(peer);
        if (known == null || !known.external()) {
            // Either the project does not list it - attribution already decided that is allowed -
            // or it is vouched, and a vouched peer's machine has already done this.
            return "";
        }
        if (filter == null) {
            return "it is from an external peer and no message filter is installed to check it";
        }

        final String name = message.getFileName().toString();
        clear(mailbox);
        Files.copy(message, mailbox.checkIncoming().resolve(name),
                StandardCopyOption.REPLACE_EXISTING);
        final CommandResult result;
        try {
            result = runner.run(Command.of(filter.toString(), "--mail",
                    mailbox.check().toString(), "--blocking"));
        } catch (final RuntimeException e) {
            return "it is from an external peer and the filter could not be run: " + e.getMessage();
        }
        if (result.exitCode() != MessageFiltering.ALL_ACCEPTED
                && result.exitCode() != MessageFiltering.SOMETHING_REFUSED) {
            return "it is from an external peer and the filter stopped with " + result.exitCode();
        }
        if (Files.isRegularFile(mailbox.checkAccepted().resolve(name))) {
            clear(mailbox);
            return "";
        }
        final boolean unreadable = Files.isRegularFile(mailbox.checkError().resolve(name));
        keepTheAnswer(mailbox, name);
        clear(mailbox);
        return unreadable ? "it is from an external peer and the filter could not read it"
                : "the filter refused it on the way in";
    }

    /**
     * Keeps the filter's own answer beside the message that is about to be held.
     * <p>
     * It is never sent - it would tell an unvouched sender what this machine looks for - but it is
     * the only record of why, and an operator deciding whether to release the message needs it.
     * Nothing in it holds what a rule matched: the filter redacts before it writes.
     *
     * @param mailbox The task's mailbox.
     * @param name The message's file name, which the answer is named after.
     * @throws IOException Moving failed.
     */
    private void keepTheAnswer(final Mailbox mailbox, final String name) throws IOException {
        if (!Files.isDirectory(mailbox.checkFeedback())) {
            return;
        }
        try (Stream<Path> answers = Files.list(mailbox.checkFeedback())) {
            int number = 0;
            for (final Path answer : answers.filter(Files::isRegularFile).sorted().toList()) {
                number++;
                Files.move(answer, mailbox.hold().resolve(name + ".refusal-" + number + ".json"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Empties the check area.
     * <p>
     * Before and after, and not only after: one message is checked at a time, and something left
     * behind by a crash must never be mistaken for this message's verdict.
     *
     * @param mailbox The task's mailbox.
     * @throws IOException Deleting failed.
     */
    private void clear(final Mailbox mailbox) throws IOException {
        for (final Path directory : List.of(mailbox.checkIncoming(), mailbox.checkAccepted(),
                mailbox.checkRejected(), mailbox.checkFeedback(), mailbox.checkError())) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(directory)) {
                for (final Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }
}
