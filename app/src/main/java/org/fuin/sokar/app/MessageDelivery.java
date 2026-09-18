package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.sokar.vault.SshSignature;

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
     * What one pass did.
     *
     * @param delivered Messages handed to the agent.
     * @param held Messages that reached no task.
     */
    public record Outcome(List<String> delivered, List<Held> held) {
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
        final List<String> delivered = new ArrayList<>();
        final List<Held> held = new ArrayList<>();
        for (final Path message : arrived(mailbox.inbound())) {
            final String name = message.getFileName().toString();
            final Path signature = mailbox.inbound().resolve(name + ".sig");
            if (!Files.isRegularFile(signature)) {
                hold(mailbox, message, null);
                held.add(new Held(name, "it arrived without a signature"));
                continue;
            }
            final byte[] bytes = Files.readAllBytes(message);
            final String armored = Files.readString(signature, StandardCharsets.UTF_8);
            final byte[] signer = SshSignature.signerOf(armored);
            final Peer peer = signer == null ? null : peerFor(peers, signer);
            if (peer == null) {
                hold(mailbox, message, signature);
                held.add(new Held(name, signer == null ? "its signature cannot be read"
                        : "it is signed by a key no peer is allowed to use"));
                continue;
            }
            if (!SshSignature.verify(bytes, armored, signer, MessageIntake.NAMESPACE)) {
                hold(mailbox, message, signature);
                held.add(new Held(name,
                        "its signature does not match the message it arrived with"));
                continue;
            }

            // Into the agent's half through tmp and a rename, so a reader in the container never
            // sees half a message - the same discipline the agent is asked to use on the way out.
            final Path staged = mailbox.inboxTmp().resolve(name);
            Files.move(message, staged, StandardCopyOption.REPLACE_EXISTING);
            Files.move(staged, mailbox.inboxNew().resolve(name), StandardCopyOption.ATOMIC_MOVE);
            // The signature stays on the host's side: it is evidence about the message, and the
            // container has no key to check it with anyway.
            Files.move(signature, mailbox.record().resolve(name + ".sig"),
                    StandardCopyOption.REPLACE_EXISTING);
            delivered.add(name);
        }
        return new Outcome(delivered, held);
    }

    private Peer peerFor(final List<Peer> peers, final byte[] signer) {
        for (final Peer peer : peers) {
            for (final byte[] key : peer.keys()) {
                if (Arrays.equals(key, signer)) {
                    return peer;
                }
            }
        }
        return null;
    }

    private void hold(final Mailbox mailbox, final Path message, final Path signature)
            throws IOException {
        Files.move(message, mailbox.hold().resolve(message.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING);
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
