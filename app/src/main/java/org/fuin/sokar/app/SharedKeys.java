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
 * <strong>A key is believed only if its file is owned by the user it is named after.</strong>
 * {@code keys/bob.pub} counts as bob's key when bob owns it, and counts as nothing otherwise. The
 * directory is sticky, so once bob has published, nobody can replace his file; and before he has,
 * somebody else creating it produces a file they own, which fails this check. So the first
 * publication cannot be taken either.
 * <p>
 * That is the same kind of fact as the one a transport attests about a message: the kernel says who
 * wrote a file, and nothing in the file can contradict it. Agreed with the filter's agent on
 * 2026-09-18 (their issue 010).
 */
public final class SharedKeys {

    /** What a published key file is called, after the user it belongs to. */
    public static final String SUFFIX = ".pub";

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
        try (Stream<Path> files = Files.list(directory)) {
            for (final Path file : files.sorted().toList()) {
                final String name = file.getFileName().toString();
                if (!Files.isRegularFile(file) || !name.endsWith(SUFFIX)) {
                    continue;
                }
                final String user = name.substring(0, name.length() - SUFFIX.length());
                if (!ownedBy(file, user)) {
                    // Not an error and not a warning: a file somebody put there in another's name
                    // is exactly what this check exists for, and it simply is not a key.
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
