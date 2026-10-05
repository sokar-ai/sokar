package org.fuin.sokar.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import org.jspecify.annotations.Nullable;

/**
 * Detached signatures in OpenSSH's {@code SSHSIG} format.
 * <p>
 * The format OpenSSH's {@code ssh-keygen -Y sign} and {@code -Y verify} speak, so a signature made
 * here can be checked by somebody who has no Sokar - with the key in an {@code allowed_signers}
 * file and nothing else. That is the whole reason for using it rather than a shape of our own: a
 * record that only this program can verify is a record nobody else can audit.
 * <p>
 * <strong>Detached, and over the exact bytes.</strong> The message is never re-serialized,
 * reformatted or re-indented anywhere between signing and verifying, which is why the filter that
 * reads a message is forbidden to rewrite it.
 */
public final class SshSignature {

    /** What OpenSSH puts in front of both the blob and the bytes that are signed. */
    private static final byte[] MAGIC = "SSHSIG".getBytes(StandardCharsets.US_ASCII);

    private static final int VERSION = 1;

    private static final String HASH = "sha512";

    private static final String BEGIN = "-----BEGIN SSH SIGNATURE-----";

    private static final String END = "-----END SSH SIGNATURE-----";

    private SshSignature() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Signs a message, detached.
     *
     * @param key Key to sign with.
     * @param message The exact bytes to sign.
     * @param namespace What the signature is for. Verification must use the same one, which is how
     *        a signature over a message cannot be replayed as a signature over something else.
     * @return The armored signature, as {@code ssh-keygen -Y sign} writes it.
     */
    public static String sign(final SigningKey key, final byte[] message, final String namespace) {
        final byte[] signature = key.sign(preimage(message, namespace));
        try {
            final ByteArrayOutputStream blob = new ByteArrayOutputStream();
            blob.write(MAGIC);
            writeInt(blob, VERSION);
            SshWire.writeString(blob, key.keyBlob());
            SshWire.writeString(blob, namespace);
            SshWire.writeString(blob, new byte[0]);
            SshWire.writeString(blob, HASH);
            SshWire.writeString(blob, signature);
            return armor(blob.toByteArray());
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot write the signature", e);
        }
    }

    /**
     * Verifies a detached signature against one key.
     *
     * @param message The exact bytes that were signed.
     * @param armored The signature, as {@link #sign} returns it.
     * @param keyBlob The OpenSSH public key blob the signature must carry and verify against.
     * @param namespace The namespace the signature must carry.
     * @return {@code true} when the signature is this key's, over these bytes, in this namespace.
     */
    public static boolean verify(final byte[] message, final String armored, final byte[] keyBlob,
            final String namespace) {
        final Parsed parsed = parse(armored);
        if (parsed == null || !Arrays.equals(parsed.keyBlob(), keyBlob)
                || !namespace.equals(parsed.namespace()) || !HASH.equals(parsed.hash())) {
            return false;
        }
        return SigningKey.verify(parsed.keyBlob(), preimage(message, namespace),
                parsed.signature());
    }

    /**
     * Returns the public key a signature claims to be from, so a caller can decide whether that key
     * is allowed to speak for a peer before it verifies anything.
     *
     * @param armored The signature.
     * @return The OpenSSH public key blob, or {@code null} when this is not a signature.
     */
    public static byte @Nullable [] signerOf(final String armored) {
        final Parsed parsed = parse(armored);
        return parsed == null ? null : parsed.keyBlob();
    }

    private static byte[] preimage(final byte[] message, final String namespace) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(MAGIC);
            SshWire.writeString(out, namespace);
            SshWire.writeString(out, new byte[0]);
            SshWire.writeString(out, HASH);
            SshWire.writeString(out, MessageDigest.getInstance("SHA-512").digest(message));
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot build the signed data", e);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-512 is not available", e);
        }
    }

    private static String armor(final byte[] blob) {
        final String base64 = Base64.getEncoder().encodeToString(blob);
        final StringBuilder out = new StringBuilder(BEGIN).append('\n');
        for (int start = 0; start < base64.length(); start += 70) {
            out.append(base64, start, Math.min(start + 70, base64.length())).append('\n');
        }
        return out.append(END).append('\n').toString();
    }

    private static @Nullable Parsed parse(final String armored) {
        final String body = armored.replace(BEGIN, "").replace(END, "").replaceAll("\\s", "");
        final byte[] blob;
        try {
            blob = Base64.getDecoder().decode(body);
        } catch (final IllegalArgumentException e) {
            return null;
        }
        if (blob.length < MAGIC.length + 4
                || !Arrays.equals(Arrays.copyOf(blob, MAGIC.length), MAGIC)) {
            return null;
        }
        final ByteBuffer buffer = ByteBuffer.wrap(blob, MAGIC.length, blob.length - MAGIC.length);
        try {
            if (buffer.getInt() != VERSION) {
                return null;
            }
            final byte[] key = SshWire.readString(buffer);
            final byte[] namespace = SshWire.readString(buffer);
            SshWire.readString(buffer);
            final byte[] hash = SshWire.readString(buffer);
            final byte[] signature = SshWire.readString(buffer);
            return new Parsed(key, new String(namespace, StandardCharsets.UTF_8),
                    new String(hash, StandardCharsets.UTF_8), signature);
        } catch (final RuntimeException e) {
            // A truncated or malformed signature is not a signature. It is never an exception to
            // the caller, because "this did not verify" is the only answer that matters.
            return null;
        }
    }

    private static void writeInt(final ByteArrayOutputStream out, final int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private record Parsed(byte[] keyBlob, String namespace, String hash, byte[] signature) {
    }
}
