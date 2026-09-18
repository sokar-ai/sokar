package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The message keys the other users of this machine have published, and which of them to believe.
 * <p>
 * <strong>A key is believed only if its file and the directory holding it are both owned by the
 * user they are named after.</strong> {@code keys/bob/key.pub} counts as bob's key when bob owns
 * both, and counts as nothing otherwise.
 * <p>
 * <strong>A directory each, rather than a file each in one shared directory.</strong> Measured on
 * 2026-09-18: with one shared directory, a group member could create {@code keys/bob.pub} before
 * bob ever published. The owner check disbelieved it correctly - and the sticky bit then stopped
 * bob removing it, so bob could never publish and his messaging was silenced until root
 * intervened. Nothing was forged and nothing could be repaired. With a directory per account, made
 * by root, there is no name left to take. Found by the filter's agent, confirmed here.
 * <p>
 * That is the same kind of fact as the one a transport attests about a message: the kernel says who
 * wrote a file, and nothing in the file can contradict it. Agreed with the filter's agent on
 * 2026-09-18 (their issue 010).
 */
public final class SharedKeys {

    /** What a published key file is called, inside its account's own directory. */
    public static final String FILE = "key.pub";

    private SharedKeys() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the keys this machine's users have published to each other.
     *
     * @param directory Where they publish, which need not exist.
     * @return One peer per believable file, named after the user that owns it. Empty when
     *         messaging between users was never allowed here.
     * @throws IOException Reading failed.
     */
    public static List<MessageDelivery.Peer> read(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        final List<MessageDelivery.Peer> peers = new ArrayList<>();
        try (Stream<Path> accounts = Files.list(directory)) {
            for (final Path account : accounts.sorted().toList()) {
                final String user = account.getFileName().toString();
                final Path file = account.resolve(FILE);
                if (!Files.isDirectory(account) || !Files.isRegularFile(file)) {
                    continue;
                }
                // Both: the directory says who may write here, the file says who did. One without
                // the other is not a key - and neither is an error worth reporting, because a
                // stranger's file in a stranger's directory is exactly what this expects to find.
                if (!ownedBy(account, user) || !ownedBy(file, user)) {
                    continue;
                }
                peers.addAll(AllowedSigners.parse(Files.readAllLines(file), file));
            }
        }
        return List.copyOf(peers);
    }

    /**
     * Says whether a file belongs to the user it is named after.
     *
     * @param file The file.
     * @param user The user its name claims.
     * @return {@code true} when the kernel agrees.
     */
    static boolean ownedBy(final Path file, final String user) {
        try {
            final UserPrincipal owner = Files.getOwner(file);
            return owner != null && owner.getName().equals(user);
        } catch (final IOException | UnsupportedOperationException ex) {
            // A filesystem that cannot say who owns a file cannot support this scheme, and
            // answering "yes" would turn that into a way in.
            return false;
        }
    }
}
