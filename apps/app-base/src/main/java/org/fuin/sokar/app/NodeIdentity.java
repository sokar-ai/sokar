package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/**
 * What a node calls itself, so a client can tell two of them apart - and tell one of them from
 * itself seen twice.
 * <p>
 * <strong>Why a client cannot work this out.</strong> A hostname has many spellings, and a socket
 * somebody else forwarded looks nothing like your own tunnel to the same place. So the same node
 * can appear twice in a list of machines, and nothing on the wire says so. The consequence is not
 * cosmetic: every clearance question then arrives twice, and answering one leaves the other on
 * screen, expiring. Only the node can say who it is.
 * <p>
 * <strong>Random, and not derived from the machine.</strong> {@code /etc/machine-id} would be
 * wrong twice over: it is the same for two accounts that are two different nodes, and it is a
 * stable machine-wide identifier that nothing here has a reason to hand out. A random value in the
 * node's own data directory is per OS user for free, because that directory already is.
 * <p>
 * It says nothing about the node. Two clients comparing it learn only whether they are looking at
 * the same one, which is the whole question.
 */
public final class NodeIdentity {

    private NodeIdentity() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns this node's identity, creating it the first time it is asked for.
     * <p>
     * <strong>Two daemons starting at once must not answer differently.</strong> The obvious
     * shape - create the file, then write into it - has a gap a test found: the loser sees the
     * file exist and reads it <em>before</em> the winner has written anything, and answers with an
     * empty string. Two nodes both answering "" is exactly the failure this class exists to
     * prevent, arriving by another door.
     * <p>
     * So the value is written to a temporary file first and published by an atomic hard link,
     * which either succeeds or finds the name taken. The name therefore never exists holding
     * nothing, and whoever loses the race reads a file that was complete before it appeared.
     *
     * @param file Where the identity lives.
     * @return The identity, stable across restarts.
     * @throws java.io.UncheckedIOException If it can neither be read nor created.
     */
    public static String of(Path file) {
        try {
            final String existing = readIfPresent(file);
            if (existing != null) {
                return existing;
            }
            Files.createDirectories(file.getParent());
            final Path temp = Files.createTempFile(file.getParent(), "node-id", ".tmp");
            try {
                final String minted = UUID.randomUUID().toString();
                Files.writeString(temp, minted + "\n", StandardCharsets.UTF_8);
                ownerOnly(temp);
                try {
                    Files.createLink(file, temp);
                    return minted;
                } catch (FileAlreadyExistsException raced) {
                    return required(file);
                } catch (UnsupportedOperationException noLinks) {
                    // A filesystem without hard links. Fall back to creating the name directly,
                    // which reopens the gap above - so the read below tolerates a file that is
                    // there and not yet written.
                    try {
                        Files.writeString(file, minted + "\n", StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                        ownerOnly(file);
                        return minted;
                    } catch (FileAlreadyExistsException raced) {
                        return required(file);
                    }
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException("Cannot read or create " + file, ex);
        }
    }

    /**
     * Returns what the file holds, or {@code null} when there is nothing usable in it.
     * <p>
     * An empty file is treated as absent rather than as an identity. A run interrupted before the
     * value was written leaves one, and reading it back would have two nodes both answering "".
     *
     * @param file The identity file.
     * @return The identity, or {@code null}.
     * @throws IOException If it exists and cannot be read.
     */
    @org.jspecify.annotations.Nullable
    private static String readIfPresent(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        final String text = Files.readString(file, StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            Files.delete(file);
            return null;
        }
        return text;
    }

    /**
     * Returns what the winner of a race wrote, waiting briefly if it is not there yet.
     * <p>
     * Only the link-less fallback can see a file that exists and is empty. The wait is bounded
     * and short: this is a race between two processes over one write of thirty-six bytes.
     *
     * @param file The identity file.
     * @return The identity.
     * @throws IOException If nothing readable appears.
     */
    private static String required(Path file) throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            final String found = readIfPresent(file);
            if (found != null) {
                return found;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new IOException("Another process created " + file + " and never wrote to it");
    }

    private static void ownerOnly(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem, or no permission to change it. The value is not a secret -
            // it is an identifier that says nothing about the node - so failing a daemon's start
            // over its mode would cost more than it protects.
        }
    }
}
