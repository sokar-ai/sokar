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
 * Lets a person send a held message on its way, or refuse it for good.
 * <p>
 * <strong>Released or refused, never edited.</strong> What the record shows a task said has to be
 * what the task said; a person who may rewrite it on the way out turns the record into a record of
 * what somebody was willing to admit to. Somebody who disagrees with a message refuses it and says
 * so, which is a thing the sender can read and act on.
 * <p>
 * A released message goes back where the filter left it, so the next pass treats it exactly like
 * any other accepted message - one path out, not two.
 */
public final class MessageRelease {

    /** What happened. */
    public enum Outcome {

        /** It is on its way again. */
        RELEASED,

        /** It will not be sent, and the sender has been told. */
        REFUSED,

        /** Nothing held is called that. */
        NO_SUCH_MESSAGE,

        /** Several held messages answer to that name, so a person says which. */
        AMBIGUOUS
    }

    /**
     * What one decision did.
     *
     * @param outcome What happened.
     * @param message The file it acted on, or "".
     * @param id The message id, or "" when the file could not be read.
     */
    public record Result(Outcome outcome, String message, String id) {
    }

    /**
     * Releases or refuses one held message.
     *
     * @param mailbox The task's mailbox.
     * @param id The message id, or the file name; a person reads both off {@code talk held}.
     * @param refuse {@code true} to refuse it for good, {@code false} to let it go.
     * @return What happened.
     * @throws IOException Reading or moving failed.
     */
    public Result decide(final Mailbox mailbox, final String id, final boolean refuse)
            throws IOException {
        final List<Path> found = find(mailbox, id);
        if (found.size() != 1) {
            return new Result(found.isEmpty() ? Outcome.NO_SUCH_MESSAGE : Outcome.AMBIGUOUS, "",
                    "");
        }
        final Path held = found.get(0);
        final String name = held.getFileName().toString();
        final String messageId = MessageFile.id(held);
        final Path signature = mailbox.hold().resolve(name + ".sig");
        final Path target = refuse ? mailbox.rejected() : mailbox.accepted();
        Files.createDirectories(target);
        if (refuse) {
            // Written before the message moves: an answer that exists for a message still in
            // 'hold' is a repeat at worst, and a message gone with no answer is a sender left
            // waiting.
            new HostBounce().write(mailbox, held, "a person refused to send it", true);
        }
        if (Files.isRegularFile(signature)) {
            // The signature travels with it. It was made over these exact bytes on the way in, and
            // re-signing on release would put the host's name on a decision a person made.
            Files.move(signature, target.resolve(name + ".sig"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(held, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        return new Result(refuse ? Outcome.REFUSED : Outcome.RELEASED, name, messageId);
    }

    /**
     * Lists what is waiting for a person.
     *
     * @param mailbox The task's mailbox.
     * @return The held messages, by file name.
     * @throws IOException Reading failed.
     */
    public List<Path> waiting(final Mailbox mailbox) throws IOException {
        if (!Files.isDirectory(mailbox.hold())) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(mailbox.hold())) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }

    private List<Path> find(final Mailbox mailbox, final String id) throws IOException {
        final List<Path> candidates = new ArrayList<>();
        for (final Path held : waiting(mailbox)) {
            if (held.getFileName().toString().equals(id) || id.equals(MessageFile.id(held))) {
                candidates.add(held);
            }
        }
        return candidates;
    }
}
