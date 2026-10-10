package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.fuin.sokar.vault.SshSignature;
import org.jspecify.annotations.Nullable;

/**
 * Hands a task what arrived for it, and only what can be attributed.
 * <p>
 * A transport delivers into the mailbox's {@code inbound}; this is the step between that and the
 * agent's own inbox. What it decides is <strong>not</strong> what a message contains - the machine
 * that wrote it decided that - but whether anybody can say who wrote it.
 * <p>
 * <strong>What cannot be attributed is held, never delivered and never deleted.</strong> An agent
 * acting on a message nobody can attribute is the failure this step exists to prevent, and deleting
 * it instead would hide that somebody tried.
 */
public final class MessageDelivery {

    /** Beside a person's words a transport handed over: who wrote them, by the name they joined with. */
    public static final String PERSON_SUFFIX = ".person";

    /**
     * What a transport proved about who owned an arriving message.
     * <p>
     * A function rather than a flag, because whether anything is attested depends on the transport
     * that carried each message, and only the caller knows which that was.
     */
    @FunctionalInterface
    public interface Attested {

        /**
         * Says whether an arriving message may be delivered, as far as ownership goes.
         *
         * @param mailbox The task's mailbox.
         * @param message The message, still in {@code inbound}.
         * @param peer The peer whose key signed it.
         * @return An empty string when it may go on, otherwise why it may not.
         * @throws IOException Reading failed.
         */
        String refuse(Mailbox mailbox, java.nio.file.Path message, String peer) throws IOException;
    }

    /**
     * One peer, and the keys allowed to speak for it.
     *
     * @param name Peer name, as the host knows it.
     * @param keys OpenSSH public key blobs.
     */
    public record Peer(String name, List<byte[]> keys) {
    }

    /**
     * One message that was not delivered.
     *
     * @param message File name.
     * @param reason Why, in words an operator can act on.
     */
    public record Held(String message, String reason) {
    }

    /**
     * One message that arrived again after having been delivered.
     *
     * @param message File name it arrived under this time.
     * @param id The id it repeats, which is what names it in the record.
     */
    public record Repeat(String message, String id) {
    }

    /**
     * One message handed to the agent.
     *
     * @param message File name.
     * @param id Its message id, or "" when it carried none.
     * @param peer The peer whose key signed it.
     */
    public record Delivered(String message, String id, String peer) {
    }

    /**
     * What one pass did.
     *
     * @param delivered Messages handed to the agent.
     * @param held Messages that reached no task.
     * @param duplicates Messages carrying an id this task has already been handed.
     */
    public record Outcome(List<Delivered> delivered, List<Held> held,
            List<Repeat> duplicates) {
    }

    /**
     * Delivers what can be attributed and holds what cannot.
     *
     * @param mailbox The task's mailbox.
     * @param peers The peers this task may hear from, with the keys allowed for each.
     * @return What was delivered and what was held.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(final Mailbox mailbox, final List<Peer> peers) throws IOException {
        return deliver(mailbox, peers, Set.of());
    }

    /**
     * Delivers what can be attributed, holds what cannot, and drops what has been delivered before.
     *
     * @param mailbox The task's mailbox.
     * @param peers The peers this task may hear from, with the keys allowed for each.
     * @param seen Ids this task has already been handed, from its record.
     * @return What was delivered, what was held and what was a repeat.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(final Mailbox mailbox, final List<Peer> peers, final Set<String> seen)
            throws IOException {
        return deliver(mailbox, peers, seen, null);
    }

    /**
     * Delivers what can be attributed and, where the peer is external, what the filter also passed.
     *
     * @param mailbox The task's mailbox.
     * @param peers The peers this task may hear from, with the keys allowed for each.
     * @param seen Ids this task has already been handed, from its record.
     * @param check What reads an external peer's message before an agent does, or {@code null}
     *        when nothing does - which is only ever the case where no peer is external.
     * @return What was delivered, what was held and what was a repeat.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(final Mailbox mailbox, final List<Peer> peers, final Set<String> seen,
            final @org.jspecify.annotations.Nullable InboundCheck check) throws IOException {
        return deliver(mailbox, peers, seen, check, null);
    }

    /**
     * Delivers what can be attributed, what the filter passed and what is within the day's budget.
     *
     * @param mailbox The task's mailbox.
     * @param peers The peers this task may hear from, with the keys allowed for each.
     * @param seen Ids this task has already been handed, from its record.
     * @param check What reads an external peer's message before an agent does, or {@code null}.
     * @param budget How much this task and a peer may say to each other, or {@code null} for no
     *        limit.
     * @return What was delivered, what was held and what was a repeat.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(final Mailbox mailbox, final List<Peer> peers, final Set<String> seen,
            final @org.jspecify.annotations.Nullable InboundCheck check,
            final @org.jspecify.annotations.Nullable MessageBudget budget) throws IOException {
        return deliver(mailbox, peers, seen, check, budget, null);
    }

    /**
     * Delivers what can be attributed, what a transport's attestation agrees with, what the filter
     * passed and what is within the day's budget.
     *
     * @param mailbox The task's mailbox.
     * @param peers The peers this task may hear from, with the keys allowed for each.
     * @param seen Ids this task has already been handed, from its record.
     * @param check What reads an external peer's message before an agent does, or {@code null}.
     * @param budget How much this task and a peer may say to each other, or {@code null}.
     * @param attested What a transport proved about who owned a message, or {@code null} when
     *        nothing that carried into this mailbox attests anything.
     * @return What was delivered, what was held and what was a repeat.
     * @throws IOException Reading or moving failed.
     */
    public Outcome deliver(final Mailbox mailbox, final List<Peer> peers, final Set<String> seen,
            final @org.jspecify.annotations.Nullable InboundCheck check,
            final @org.jspecify.annotations.Nullable MessageBudget budget,
            final @org.jspecify.annotations.Nullable Attested attested) throws IOException {
        final List<Delivered> delivered = new ArrayList<>();
        final List<Held> held = new ArrayList<>();
        final List<Repeat> duplicates = new ArrayList<>();
        final Set<String> already = new LinkedHashSet<>(seen);
        final java.util.Map<String, Integer> handedOver = new java.util.HashMap<>();
        for (final Path message : arrived(mailbox.inbound())) {
            final String name = message.getFileName().toString();
            final Path signature = mailbox.inbound().resolve(name + ".sig");
            final Path person = mailbox.inbound().resolve(name + PERSON_SUFFIX);
            if (!Files.isRegularFile(signature) && Files.isRegularFile(person)) {
                // A person's words, which their conversation's transport handed over and the host took from a member
                // of it: no key signs them, and they are not filtered (2026-10-04) - the filter stands
                // between an agent and everybody else, not between a person and the agent.
                final String who = Files.readString(person, StandardCharsets.UTF_8).strip();
                final String id;
                try {
                    id = MessageFile.id(message);
                } catch (final RuntimeException ex) {
                    hold(mailbox, message, null);
                    Files.deleteIfExists(person);
                    held.add(new Held(name, "it is not a message that can be read"));
                    continue;
                }
                if (!id.isBlank() && !already.add(id)) {
                    Files.delete(message);
                    Files.delete(person);
                    duplicates.add(new Repeat(name, id));
                    continue;
                }
                mailbox.refuseLinks();
                final Path staged = mailbox.inboxTmp().resolve(name);
                Files.move(message, staged, StandardCopyOption.REPLACE_EXISTING);
                mailbox.intoInbox(staged);
                Files.move(person, mailbox.record().resolve(name + PERSON_SUFFIX), StandardCopyOption.REPLACE_EXISTING);
                delivered.add(new Delivered(name, id, who));
                continue;
            }
            if (!Files.isRegularFile(signature)) {
                hold(mailbox, message, null);
                held.add(new Held(name, "it arrived without a signature"));
                continue;
            }
            final byte[] bytes = Files.readAllBytes(message);
            final String armored = Files.readString(signature, StandardCharsets.UTF_8);
            final byte[] signer = SshSignature.signerOf(armored);
            if (signer == null) {
                hold(mailbox, message, signature);
                held.add(new Held(name, "its signature cannot be read"));
                continue;
            }
            final Peer peer = peerFor(peers, signer);
            if (peer == null) {
                hold(mailbox, message, signature);
                held.add(new Held(name, "it is signed by a key no peer is allowed to use"));
                continue;
            }
            if (!SshSignature.verify(bytes, armored, signer, MessageIntake.NAMESPACE)) {
                hold(mailbox, message, signature);
                held.add(new Held(name,
                        "its signature does not match the message it arrived with"));
                continue;
            }

            final String id;
            try {
                id = MessageFile.id(message);
            } catch (final RuntimeException ex) {
                // Held, not thrown: the pass ended here, the file stayed in inbound, and the task never received
                // anything again.
                hold(mailbox, message, signature);
                held.add(new Held(name, "it is not a message that can be read"));
                continue;
            }
            if (!id.isBlank() && !already.add(id)) {
                // A transport that hands the same message over twice - after a retry it could not
                // confirm, say - must not make the agent read it twice. The record keeps the fact
                // that it arrived again; keeping the file as well would only grow a pile that
                // nobody can act on, because the agent already has this exact message.
                Files.delete(message);
                Files.delete(signature);
                duplicates.add(new Repeat(name, id));
                continue;
            }

            // Before anything else about the message: who put it here is a fact that stops being
            // knowable once it has been moved, and it is the one the peer table can disagree with.
            final String owner = attested == null ? ""
                    : attested.refuse(mailbox, message, peer.name());
            if (!owner.isEmpty()) {
                hold(mailbox, message, signature);
                held.add(new Held(name, owner));
                continue;
            }

            final String full = budget == null ? ""
                    : budget.inbound(peer.name(), handedOver.getOrDefault(peer.name(), 0));
            if (!full.isEmpty()) {
                // Held rather than refused back: this machine is the one that is full, and the
                // message itself is nobody's fault.
                hold(mailbox, message, signature);
                held.add(new Held(name, full));
                continue;
            }

            // After attribution and after the repeat check: there is no point reading a message
            // nobody can attribute, and none in reading the same one twice.
            final String refused = check == null ? "" : check.refuse(mailbox, message, peer.name());
            if (!refused.isEmpty()) {
                hold(mailbox, message, signature);
                held.add(new Held(name, refused));
                continue;
            }

            // Into the agent's half through tmp and a rename, so a reader in the container never
            // sees half a message - the same discipline the agent is asked to use on the way out.
            mailbox.refuseLinks();
            final Path staged = mailbox.inboxTmp().resolve(name);
            Files.move(message, staged, StandardCopyOption.REPLACE_EXISTING);
            mailbox.intoInbox(staged);
            // The signature stays on the host's side: it is evidence about the message, and the
            // container has no key to check it with anyway.
            Files.move(signature, mailbox.record().resolve(name + ".sig"),
                    StandardCopyOption.REPLACE_EXISTING);
            // And so does what a transport attested about it, for the same reason and one more:
            // left in 'inbound' it would pile up unread, and the next pass would find a file
            // belonging to a message that is no longer there.
            final Path ownerFile = mailbox.inbound().resolve(name + OwnerAttestation.SUFFIX);
            if (Files.isRegularFile(ownerFile)) {
                Files.move(ownerFile, mailbox.record().resolve(name + OwnerAttestation.SUFFIX),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            delivered.add(new Delivered(name, id, peer.name()));
            handedOver.merge(peer.name(), 1, Integer::sum);
        }
        return new Outcome(delivered, held, duplicates);
    }

    private @Nullable Peer peerFor(final List<Peer> peers, final byte[] signer) {
        for (final Peer peer : peers) {
            for (final byte[] key : peer.keys()) {
                if (Arrays.equals(key, signer)) {
                    return peer;
                }
            }
        }
        return null;
    }

    private void hold(final Mailbox mailbox, final Path message, final @Nullable Path signature)
            throws IOException {
        final String name = message.getFileName().toString();
        // What a transport attested travels with the message it is about: an operator looking at a
        // held message needs to see why, and "the owner was somebody else" is only readable if the
        // file saying so is beside it.
        final Path attested = mailbox.inbound().resolve(name + OwnerAttestation.SUFFIX);
        if (Files.isRegularFile(attested)) {
            Files.move(attested, mailbox.hold().resolve(name + OwnerAttestation.SUFFIX),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(message, mailbox.hold().resolve(name), StandardCopyOption.REPLACE_EXISTING);
        if (signature != null) {
            Files.move(signature, mailbox.hold().resolve(signature.getFileName().toString()),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private List<Path> arrived(final Path inbound) throws IOException {
        if (!Files.isDirectory(inbound)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(inbound)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }
}
