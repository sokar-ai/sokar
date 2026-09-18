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
 * machine looks for. It is held, and the operator is the one who is told.
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
                    mailbox.check().toString()));
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
        if (Files.isRegularFile(mailbox.checkError().resolve(name))) {
            clear(mailbox);
            return "it is from an external peer and the filter could not read it";
        }
        clear(mailbox);
        return "the filter refused it on the way in";
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
