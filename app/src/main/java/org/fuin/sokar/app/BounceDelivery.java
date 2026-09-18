package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Puts the filter's answers into the agent's own inbox.
 * <p>
 * A receipt or a refusal is a bounce: it arrives where the agent already reads, in the format it
 * already reads, rather than through a channel of its own. That is what a mail system does with a
 * message it could not deliver, and the reason an agent needs no special case for being refused.
 * <p>
 * <strong>A bounce goes around the filter, never through it.</strong> It is the filter's own answer,
 * written on this machine and never carried anywhere, and it carries a data part that the narrowed
 * schema refuses for anything arriving from outside. Putting it through the check that produced it
 * would refuse it for being what it is.
 * <p>
 * <strong>And it is not signature-checked</strong>, for the same reason: it never left the machine.
 * What guards it is that only this host writes into that directory.
 */
public final class BounceDelivery {

    /**
     * Delivers every answer waiting for the agent.
     *
     * @param mailbox The task's mailbox.
     * @return The answers handed over, in the order they were written.
     * @throws IOException Moving failed.
     */
    public List<String> deliver(final Mailbox mailbox) throws IOException {
        final List<String> delivered = new ArrayList<>();
        for (final Path answer : answers(mailbox.feedback())) {
            final String name = answer.getFileName().toString();
            // Through tmp and a rename, so the agent never lists half an answer - the same
            // discipline every other hop in the mailbox keeps.
            final Path staged = mailbox.inboxTmp().resolve(name);
            Files.move(answer, staged, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staged, mailbox.inboxNew().resolve(name), StandardCopyOption.ATOMIC_MOVE);
            delivered.add(name);
        }
        return delivered;
    }

    private List<Path> answers(final Path feedback) throws IOException {
        if (!Files.isDirectory(feedback)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(feedback)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }
}
