package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.fuin.sokar.vault.SshSignature;

/**
 * Walks a task's record and says what no longer checks out.
 * <p>
 * Two different questions, answered in one pass because an operator asking either is really asking
 * "can I still believe this mailbox":
 * <ul>
 * <li><strong>The chain</strong> - was a line edited or removed after it was written.</li>
 * <li><strong>The signatures</strong> - does each kept signature still match the message it was
 * made over.</li>
 * </ul>
 * <p>
 * <strong>It names the first thing that fails and keeps going.</strong> Stopping at the first would
 * hide how far the damage reaches, and a list with nothing first in it makes a person read all of
 * it to find out whether anything matters.
 */
public final class MessageVerify {

    /**
     * One thing that does not check out.
     *
     * @param what The line number, or the file name.
     * @param detail Why, in words an operator can act on.
     */
    public record Fault(String what, String detail) {
    }

    /**
     * What the walk found.
     *
     * @param lines How many record lines were read.
     * @param signatures How many kept signatures were checked.
     * @param faults What does not check out, in the order found. Empty means it all holds.
     */
    public record Report(int lines, int signatures, List<Fault> faults) {
    }

    /**
     * Checks one mailbox.
     *
     * @param mailbox The task's mailbox.
     * @param peers The peers whose keys may have signed what arrived.
     * @return What was found.
     * @throws IOException Reading failed.
     */
    public Report check(final Mailbox mailbox, final List<MessageDelivery.Peer> peers)
            throws IOException {
        final List<Fault> faults = new ArrayList<>();
        final MessageRecord record = new MessageRecord(mailbox);
        final int broken = record.firstBrokenLine();
        if (broken > 0) {
            faults.add(new Fault("line " + broken,
                    "it does not follow the line before it, so the record was changed after it was"
                            + " written"));
        }
        int checked = 0;
        for (final Path signature : kept(mailbox.record())) {
            final String name = signature.getFileName().toString();
            final String messageName = name.substring(0, name.length() - ".sig".length());
            final Path message = messageIn(mailbox, messageName);
            if (message == null) {
                // Not a fault: an agent is free to delete what it has read, and a signature for a
                // message that is gone is evidence about something nobody can check any more.
                continue;
            }
            checked++;
            final String armored = Files.readString(signature, StandardCharsets.UTF_8);
            final byte[] signer = SshSignature.signerOf(armored);
            if (signer == null || peerFor(peers, signer) == null) {
                faults.add(new Fault(messageName,
                        "its signature is not one of the keys a peer may use"));
            } else if (!SshSignature.verify(Files.readAllBytes(message), armored, signer,
                    MessageIntake.NAMESPACE)) {
                faults.add(new Fault(messageName,
                        "it no longer matches the signature it arrived with"));
            }
        }
        return new Report(record.size(), checked, List.copyOf(faults));
    }

    private MessageDelivery.@org.jspecify.annotations.Nullable Peer peerFor(
            final List<MessageDelivery.Peer> peers, final byte[] signer) {
        for (final MessageDelivery.Peer peer : peers) {
            for (final byte[] key : peer.keys()) {
                if (java.util.Arrays.equals(key, signer)) {
                    return peer;
                }
            }
        }
        return null;
    }

    private @org.jspecify.annotations.Nullable Path messageIn(final Mailbox mailbox,
            final String name) {
        for (final Path where : List.of(mailbox.inboxNew(), mailbox.inboxCur(), mailbox.sent())) {
            final Path candidate = where.resolve(name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private List<Path> kept(final Path record) throws IOException {
        if (!Files.isDirectory(record)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(record)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sig")).sorted()
                    .toList();
        }
    }
}
