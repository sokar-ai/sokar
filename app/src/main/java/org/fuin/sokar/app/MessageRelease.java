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
        AMBIGUOUS,

        /**
         * The filter had refused it, and a person who read it sent it after all - straight to its
         * transport, never through {@code accepted/}, which means the filter passed it.
         */
        DELIVERED_DESPITE_FILTER,

        /**
         * It is known and cannot be sent: a person refused it for good, the filter could not check it,
         * or it has no one this project may address. {@code detail} says which.
         */
        NOT_DELIVERABLE
    }

    /**
     * What one decision did.
     *
     * @param outcome What happened.
     * @param message The file it acted on, or "".
     * @param id The message id, or "" when the file could not be read.
     * @param detail Why, for {@link Outcome#NOT_DELIVERABLE}; "" otherwise.
     */
    public record Result(Outcome outcome, String message, String id, String detail) {

        /**
         * Constructor for an outcome that needs no why.
         *
         * @param outcome What happened.
         * @param message The file.
         * @param id The message id.
         */
        public Result(final Outcome outcome, final String message, final String id) {
            this(outcome, message, id, "");
        }
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
        return decide(mailbox, id, refuse, org.fuin.sokar.core.project.Mail.none());
    }

    /**
     * Decides about one message a person read: a held one, or one the filter refused.
     * <p>
     * <strong>A message the filter refused can be delivered after all</strong>, at the operator's word:
     * a person who read it in full decides. It goes from {@code rejected/} straight to its transport,
     * never through {@code accepted/}, so "the filter passed it" keeps one meaning, and the record says
     * it was delivered despite the filter. Refusing it keeps it refused, recorded as a person's decision.
     * <strong>What a person refused for good, and what the filter could not check at all, is never
     * sent</strong>: the first was final, the second is bytes nobody here could read.
     *
     * @param mailbox The task's mailbox.
     * @param id The message id, or the file name.
     * @param refuse {@code true} to refuse it for good, {@code false} to let it go.
     * @param mail The project's peers, which say where a refused message would be delivered.
     * @return What happened.
     * @throws IOException Reading or moving failed.
     */
    public Result decide(final Mailbox mailbox, final String id, final boolean refuse,
            final org.fuin.sokar.core.project.Mail mail) throws IOException {
        final List<MessageLookup.Found> known = MessageLookup.find(mailbox, id);
        if (known.size() != 1) {
            return new Result(known.isEmpty() ? Outcome.NO_SUCH_MESSAGE : Outcome.AMBIGUOUS, "", "");
        }
        final MessageLookup.Found one = known.get(0);
        final String file = one.file().getFileName().toString();
        return switch (one.standing()) {
            case HELD -> held(mailbox, one.file(), refuse);
            case REFUSED_BY_FILTER -> refuse ? refusedAgain(mailbox, one.file())
                    : despite(mailbox, one.file(), mail);
            case REFUSED_BY_PERSON -> new Result(Outcome.NOT_DELIVERABLE, file, MessageFile.id(one.file()),
                    "a person refused it for good, and the sender was told");
            case UNCHECKED -> new Result(Outcome.NOT_DELIVERABLE, file, "",
                    "the filter could not check it at all, so nothing here can say what a peer would read");
        };
    }

    private Result refusedAgain(final Mailbox mailbox, final Path refused) throws IOException {
        // It stays where it is; what changes is that a person has now decided, and that is final.
        final String id = MessageFile.id(refused);
        new MessageRecord(mailbox).append(MessageRecord.REFUSED, refused.getFileName().toString(), id,
                "a person refused it after reading it");
        return new Result(Outcome.REFUSED, refused.getFileName().toString(), id);
    }

    private Result despite(final Mailbox mailbox, final Path refused, final org.fuin.sokar.core.project.Mail mail)
            throws IOException {
        final String name = refused.getFileName().toString();
        final String id = MessageFile.id(refused);
        final String to = addressee(refused);
        final org.fuin.sokar.core.project.Mail.Peer peer = to.isEmpty() ? null : mail.peer(to);
        if (peer == null) {
            return new Result(Outcome.NOT_DELIVERABLE, name, id, to.isEmpty()
                    ? "it addresses nobody this host can send to" : "this project may not address '" + to + "'");
        }
        final Path signature = mailbox.rejected().resolve(name + ".sig");
        if (!Files.isRegularFile(signature)) {
            // Not signed again here: the host's signature is over the bytes it took in, and signing now
            // would put its name on a decision a person made.
            return new Result(Outcome.NOT_DELIVERABLE, name, id, "its signature is not beside it");
        }
        final Path active = mailbox.queueActive(peer.transport());
        Files.createDirectories(active);
        Files.writeString(active.resolve(name + MessageDispatch.DESTINATION_SUFFIX), peer.destination(),
                java.nio.charset.StandardCharsets.UTF_8);
        Files.move(signature, active.resolve(name + ".sig"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(refused, active.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        new MessageRecord(mailbox).append(MessageRecord.OVERRIDDEN, name, id, peer.name(),
                "the filter refused it and a person delivered it after reading it");
        return new Result(Outcome.DELIVERED_DESPITE_FILTER, name, id);
    }

    /**
     * Returns the peers of the project a task belongs to, which say where a refused message may go.
     *
     * @param context The machine.
     * @param container The task's container.
     * @return The project's peers, or none when the task names no project.
     */
    public static org.fuin.sokar.core.project.Mail peersOf(final SokarContext context, final String container) {
        final String project = new TaskInventory(context).projectOf(container);
        return project == null ? org.fuin.sokar.core.project.Mail.none() : GateSupport.byName(context, project).mail();
    }

    private static String addressee(final Path message) throws IOException {
        final Object parsed = org.fuin.sokar.wire.Json.parse(
                Files.readString(message, java.nio.charset.StandardCharsets.UTF_8));
        if (parsed instanceof java.util.Map<?, ?> document
                && document.get("metadata") instanceof java.util.Map<?, ?> metadata
                && metadata.get("to") instanceof String to) {
            return to.strip();
        }
        return "";
    }

    private Result held(final Mailbox mailbox, final Path held, final boolean refuse) throws IOException {
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
        if (refuse) {
            // Recorded, so the refusal stays a person's and final: in 'rejected/' it lies beside what
            // the filter refused, which a person may still deliver.
            new MessageRecord(mailbox).append(MessageRecord.REFUSED, name, messageId, "a person refused to send it");
        }
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

    /**
     * Finds held messages by id or file name - in {@code hold/} and nowhere else.
     * <p>
     * Nowhere else on purpose: {@code rejected/} and the filter's {@code error/} keep refused originals
     * in clear text, secrets included, and a name that resolved there would let a release send a
     * refused message out, or a read show it through a forwarded socket.
     *
     * @param mailbox The task's mailbox.
     * @param id The message id or file name.
     * @return Every held message that answers to it.
     * @throws IOException Reading failed.
     */
    List<Path> find(final Mailbox mailbox, final String id) throws IOException {
        final List<Path> candidates = new ArrayList<>();
        for (final Path held : waiting(mailbox)) {
            if (held.getFileName().toString().equals(id) || id.equals(MessageFile.id(held))) {
                candidates.add(held);
            }
        }
        return candidates;
    }
}
