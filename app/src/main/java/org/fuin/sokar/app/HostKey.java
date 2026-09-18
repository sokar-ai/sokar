package org.fuin.sokar.app;

import static java.nio.file.attribute.PosixFilePermission.OWNER_READ;
import static java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import org.fuin.sokar.vault.SigningKey;

/**
 * The key this machine signs messages with.
 * <p>
 * One key per installation, which is one Unix user: Sokar runs as a user unit, so two people on one
 * machine have two of these and neither can read the other's. That is exactly the strength of the
 * boundary - file permissions - and it is worth saying rather than implying, because a signature
 * proves which installation vouched for a message and not which agent wrote it.
 */
public final class HostKey {

    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY =
            Set.of(OWNER_READ, OWNER_WRITE);

    private HostKey() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads the key, creating one the first time.
     * <p>
     * Created rather than demanded, because a machine that has never sent a message has no reason
     * to have been asked for one - and the first message is not the moment to fail on a missing
     * file.
     *
     * @param file Where the key is kept.
     * @param comment What it is called in an {@code allowed_signers} line.
     * @return The key.
     * @throws IOException Reading or writing failed.
     */
    public static SigningKey loadOrCreate(final Path file, final String comment)
            throws IOException {
        if (Files.isRegularFile(file)) {
            final String seed = Files.readString(file, StandardCharsets.UTF_8).strip();
            return new SigningKey(Base64.getDecoder().decode(seed), comment);
        }
        final SigningKey created = SigningKey.generate(comment);
        Files.createDirectories(file.getParent());
        // Written to a temporary name and renamed, so nothing can read a half-written key, and
        // created 0600 before anything goes into it rather than after.
        final Path partial = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.writeString(partial, created.seedBase64(), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(partial, OWNER_ONLY);
        Files.move(partial, file);
        return created;
    }

    /**
     * Returns the line this machine would add to somebody's {@code allowed_signers} file.
     *
     * @param key The key.
     * @param principal What this installation is called to its peers.
     * @return One line, without a newline.
     */
    public static String allowedSignersLine(final SigningKey key, final String principal) {
        return principal + " " + key.authorizedKeysLine();
    }
}
