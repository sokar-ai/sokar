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
    public record Outcome(Map<String, String> queued, List<MessageDelivery.Held> held) {
    }

    /**
     * Queues every accepted message for the transport its peer uses.
     *
     * @param mailbox The task's mailbox.
     * @param mail The project's peers.
     * @return What was queued and what was held.
     * @throws IOException Reading or moving failed.
     */
    public Outcome dispatch(final Mailbox mailbox, final Mail mail) throws IOException {
        final Map<String, String> queued = new LinkedHashMap<>();
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
            final Path active = mailbox.queueActive(peer.transport());
            Files.createDirectories(active);
            // The destination is written down here rather than resolved again at send time: the
            // peer table can change between the two, and a message must go where it was addressed
            // when it was accepted.
            Files.writeString(active.resolve(name + DESTINATION_SUFFIX), peer.destination(),
                    StandardCharsets.UTF_8);
            move(mailbox.accepted().resolve(name + ".sig"), active.resolve(name + ".sig"));
            Files.move(message, active.resolve(name), StandardCopyOption.ATOMIC_MOVE);
            queued.put(name, peer.transport());
        }
        return new Outcome(queued, held);
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
