package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.wire.Json;

/**
 * Decides which transport carries an accepted message, and queues it for that one.
 * <p>
 * <strong>The filter never learns this.</strong> Resolving a peer name to a transport is policy,
 * and policy stays where the peer table is - which is the same reason a container never sees an
 * address. The filter writes what it accepted into one directory; this reads the peer out of the
 * message and moves it into that transport's queue.
 */
public final class MessageDispatch {

    /** Written beside a queued message: where its transport has to deliver it. */
    public static final String DESTINATION_SUFFIX = ".to";

    /**
     * What one pass did.
     *
     * @param queued Message file name to the transport it was queued for.
     * @param held Messages that reached no queue, with why.
     */
    public record Outcome(Map<String, String> queued, List<MessageDelivery.Held> held,
            Map<String, String> peers) {
    }

    /** Beside a message in {@code accepted/}: a person released it, so no mode or hold keeps it back. */
    static final String RELEASED_SUFFIX = ".released";

    /**
     * Queues every accepted message whose peer a person has not held.
     *
     * @param mailbox The task's mailbox.
     * @param mail The project's peers.
     * @param moderation What a person decided about each of them.
     * @return What was queued and what was held.
     * @throws IOException Reading or moving failed.
     */
    public Outcome dispatch(final Mailbox mailbox, final Mail mail, final Moderation moderation)
            throws IOException {
        return dispatch(mailbox, mail, moderation, null);
    }

    /**
     * Queues every accepted message whose peer a person has not held and whose budget holds.
     *
     * @param mailbox The task's mailbox.
     * @param mail The project's peers.
     * @param moderation What a person decided about each of them.
     * @param budget How much this task and a peer may say to each other, or {@code null} for no
     *        limit.
     * @return What was queued and what was held.
     * @throws IOException Reading or moving failed.
     */
    public Outcome dispatch(final Mailbox mailbox, final Mail mail, final Moderation moderation,
            final @org.jspecify.annotations.Nullable MessageBudget budget) throws IOException {
        final Map<String, String> queued = new LinkedHashMap<>();
        final Map<String, String> addressedTo = new LinkedHashMap<>();
        final Map<String, Integer> queuedNow = new LinkedHashMap<>();
        final List<MessageDelivery.Held> held = new ArrayList<>();
        for (final Path message : accepted(mailbox.accepted())) {
            final String name = message.getFileName().toString();
            final List<String> addressed = addressees(message);
            if (addressed.size() != 1) {
                hold(mailbox, message, name);
                held.add(new MessageDelivery.Held(name, addressed.isEmpty()
                        ? "it addresses nobody: metadata.to is missing or empty"
                        // Fan-out is a copy per peer and a receipt per copy. Refusing it plainly is
                        // better than delivering to the first name and saying nothing about the rest.
                        : "it addresses " + addressed.size()
                                + " peers, and sending to more than one is not built"));
                continue;
            }
            final Mail.Peer peer = mail.peer(addressed.get(0));
            if (peer == null) {
                hold(mailbox, message, name);
                held.add(new MessageDelivery.Held(name,
                        "this project may not address '" + addressed.get(0) + "'"));
                continue;
            }
            // Last, and after the peer is known to exist: a person's decision is about a peer,
            // and "held" is a different answer from "this project may not address that name".
            final Path released = mailbox.accepted().resolve(name + RELEASED_SUFFIX);
            final String waiting = Files.isRegularFile(released) && !moderation.refuses(addressed.get(0)) ? ""
                    : moderation.whyNotNow(addressed.get(0));
            if (!waiting.isEmpty()) {
                hold(mailbox, message, name);
                held.add(new MessageDelivery.Held(name, waiting));
                continue;
            }
            // And what the filter flagged, though it let it through because it only reports: only that waits for a
            // person, whatever the peer's mode (the operator in walk 10, 2026-10-04). Held until a person releases it.
            final String flagged = Files.isRegularFile(released) ? ""
                    : MessageLookup.heldBack(mailbox, MessageFile.id(message));
            if (!flagged.isEmpty()) {
                hold(mailbox, message, name);
                held.add(new MessageDelivery.Held(name, flagged));
                continue;
            }
            final String full = budget == null ? "" : budget.outbound(addressed.get(0),
                    queuedNow.getOrDefault(addressed.get(0), 0));
            if (!full.isEmpty()) {
                hold(mailbox, message, name);
                held.add(new MessageDelivery.Held(name, full));
                continue;
            }
            final Path active = mailbox.queueActive(peer.transport());
            Files.createDirectories(active);
            // The destination is written down here rather than resolved again at send time: the
            // peer table can change between the two, and a message must go where it was addressed
            // when it was accepted.
            Files.writeString(active.resolve(name + DESTINATION_SUFFIX), peer.destination(),
                    StandardCharsets.UTF_8);
            move(mailbox.accepted().resolve(name + ".sig"), active.resolve(name + ".sig"));
            Files.move(message, active.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            Files.deleteIfExists(released);
            queued.put(name, peer.transport());
            addressedTo.put(name, peer.name());
            queuedNow.merge(peer.name(), 1, Integer::sum);
        }
        return new Outcome(queued, held, addressedTo);
    }

    private List<String> addressees(final Path message) throws IOException {
        final Object parsed = Json.parse(Files.readString(message, StandardCharsets.UTF_8));
        if (!(parsed instanceof Map<?, ?> document)
                || !(document.get("metadata") instanceof Map<?, ?> metadata)) {
            return List.of();
        }
        final Object to = metadata.get("to");
        if (to instanceof String one) {
            return one.isBlank() ? List.of() : List.of(one);
        }
        if (to instanceof List<?> many) {
            return many.stream().map(String::valueOf).filter(name -> !name.isBlank()).toList();
        }
        return List.of();
    }

    private void hold(final Mailbox mailbox, final Path message, final String name)
            throws IOException {
        // Held again for another reason - a full budget, say: the release was for that once.
        Files.deleteIfExists(mailbox.accepted().resolve(name + RELEASED_SUFFIX));
        move(mailbox.accepted().resolve(name + ".sig"), mailbox.hold().resolve(name + ".sig"));
        Files.move(message, mailbox.hold().resolve(name), StandardCopyOption.REPLACE_EXISTING);
    }

    private void move(final Path from, final Path to) throws IOException {
        if (Files.isRegularFile(from)) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Path> accepted(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }
}
