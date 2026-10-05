package org.fuin.sokar.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * The SSH wire encoding: big-endian lengths and length-prefixed byte strings.
 * <p>
 * Small enough to write, and writing it avoids pulling an SSH library into the vault for four
 * message types.
 */
public final class SshWire {

    private SshWire() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Writes a length-prefixed byte string.
     *
     * @param out Target.
     * @param value Bytes to write.
     * @throws IOException If writing fails.
     */
    public static void writeString(ByteArrayOutputStream out, byte[] value) throws IOException {
        out.write(ByteBuffer.allocate(4).putInt(value.length).array());
        out.write(value);
    }

    /**
     * Writes a length-prefixed text string.
     *
     * @param out Target.
     * @param value Text to write.
     * @throws IOException If writing fails.
     */
    public static void writeString(ByteArrayOutputStream out, String value) throws IOException {
        writeString(out, value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Reads a length-prefixed byte string.
     *
     * @param buffer Source, positioned at the length.
     * @return The bytes.
     * @throws SshProtocolException If the length is missing or implausible.
     */
    public static byte[] readString(ByteBuffer buffer) {
        if (buffer.remaining() < 4) {
            throw new SshProtocolException("Truncated string length");
        }
        final int length = buffer.getInt();
        if (length < 0 || length > buffer.remaining()) {
            // A negative or oversized length is how a hostile client turns a parser into an
            // allocator. This is the only place that reads a length, so this is the only place
            // that has to say no.
            throw new SshProtocolException("Implausible string length " + length);
        }
        final byte[] value = new byte[length];
        buffer.get(value);
        return value;
    }

    /**
     * Builds the public key blob for an Ed25519 key.
     *
     * @param publicKey The raw 32-byte public key.
     * @return Key blob as OpenSSH encodes it.
     */
    public static byte[] ed25519KeyBlob(byte[] publicKey) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeString(out, "ssh-ed25519");
            writeString(out, publicKey);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new SshProtocolException("Cannot build a key blob: " + ex.getMessage());
        }
    }

    /**
     * Builds the signature blob for an Ed25519 signature.
     *
     * @param signature The raw 64-byte signature.
     * @return Signature blob as OpenSSH encodes it.
     */
    public static byte[] ed25519SignatureBlob(byte[] signature) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeString(out, "ssh-ed25519");
            writeString(out, signature);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new SshProtocolException("Cannot build a signature blob: " + ex.getMessage());
        }
    }
}
