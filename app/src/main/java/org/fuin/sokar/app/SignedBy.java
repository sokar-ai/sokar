package org.fuin.sokar.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * The public key a commit was signed with, taken out of the commit itself.
 * <p>
 * <strong>Why this exists.</strong> A machine that refuses a commit names the key's fingerprint,
 * because that is what a person can compare with what they were told. Pinning that key then meant
 * finding the whole key somewhere else and pasting it - so the refusal pointed at one string and
 * the cure needed another. With this, a person confirms the fingerprint they were shown and the
 * key itself is read from the commit.
 * <p>
 * <strong>This is not a way around the anchor.</strong> The key still comes from the person: they
 * say which fingerprint they expect, and a key whose fingerprint is anything else is refused. What
 * is read from the repository is only the bytes of a key somebody has already vouched for by name.
 * Reading a key from the repository <em>without</em> that confirmation would make the signature
 * decoration, which is the one rule with no exception.
 */
public final class SignedBy {

    private SignedBy() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** What an SSH signature blob starts with. */
    private static final byte[] MAGIC = "SSHSIG".getBytes(StandardCharsets.US_ASCII);

    /**
     * Tells whether what somebody typed is a fingerprint rather than a whole key.
     *
     * @param said What was given to {@code --signed-by}, or {@code null}.
     * @return {@code true} for {@code SHA256:...}.
     */
    public static boolean isFingerprint(final @Nullable String said) {
        return said != null && said.strip().startsWith("SHA256:");
    }

    /**
     * Returns the public key one commit was signed with.
     *
     * @param runner How git is run.
     * @param repository A clone holding that commit.
     * @param ref What to read, such as {@code FETCH_HEAD}.
     * @return The key as {@code ssh-ed25519 AAAA...}, or {@code null} when the commit carries no
     *         SSH signature or it cannot be read. Not an error here: the caller is deciding
     *         whether to trust it and has a better refusal than this could write.
     */
    public static @Nullable String keyOf(final CommandRunner runner, final Path repository,
            final String ref) {
        final CommandResult read = runner.run(Command.of("git", "-C", repository.toString(),
                "cat-file", "commit", ref));
        if (!read.successful()) {
            return null;
        }
        final String armored = armoredIn(read.standardOutput());
        if (armored == null) {
            return null;
        }
        try {
            final byte[] blob = Base64.getMimeDecoder().decode(armored);
            // SSHSIG: magic, version, then the public key as a length-prefixed string. The key's
            // own blob is what authorized_keys carries after the type, base64 and all.
            int at = MAGIC.length + 4;
            final int length = intAt(blob, at);
            at += 4;
            if (length <= 0 || at + length > blob.length) {
                return null;
            }
            final byte[] key = java.util.Arrays.copyOfRange(blob, at, at + length);
            final int typeLength = intAt(key, 0);
            if (typeLength <= 0 || 4 + typeLength > key.length) {
                return null;
            }
            final String type = new String(key, 4, typeLength, StandardCharsets.US_ASCII);
            return type + " " + Base64.getEncoder().encodeToString(key);
        } catch (final IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
            // A signature this cannot parse is one this must not guess at.
            return null;
        }
    }

    /**
     * Returns the fingerprint of a public key, the way {@code ssh-keygen -l} prints it.
     *
     * @param key A key as {@code ssh-ed25519 AAAA...}.
     * @return {@code SHA256:...}, or {@code null} when the key cannot be read.
     */
    public static @Nullable String fingerprintOf(final String key) {
        final String[] fields = key.strip().split("\\s+");
        if (fields.length < 2) {
            return null;
        }
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(Base64.getDecoder().decode(fields[1]));
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (final NoSuchAlgorithmException | IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Returns the base64 body of the {@code gpgsig} header, unwrapped.
     * <p>
     * git indents a continued header with one space and wraps the armor itself, so the lines have
     * to be stripped of both before they are one blob again.
     */
    private static @Nullable String armoredIn(final String commit) {
        final StringBuilder body = new StringBuilder();
        boolean inside = false;
        for (final String line : commit.split("\n", -1)) {
            final String stripped = line.startsWith(" ") ? line.substring(1) : line;
            // 'contains', not 'startsWith': the first line of the header is
            // "gpgsig -----BEGIN SSH SIGNATURE-----", and the armor only begins after the key's
            // own name. Found by the test rather than by reading the format.
            if (stripped.contains("-----BEGIN SSH SIGNATURE-----")) {
                inside = true;
                continue;
            }
            if (stripped.contains("-----END SSH SIGNATURE-----")) {
                return body.isEmpty() ? null : body.toString();
            }
            if (inside) {
                body.append(stripped.strip());
            }
        }
        return null;
    }

    private static int intAt(final byte[] bytes, final int at) {
        if (at + 4 > bytes.length) {
            return -1;
        }
        return ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
    }
}
