package org.fuin.sokar.vault;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The 32-byte seed inside an OpenSSH private key file.
 * <p>
 * <strong>Why this exists.</strong> The vault stores an Ed25519 signing key as base64 of its
 * 32-byte seed, which is what {@link SigningKey} takes. Nothing produced that form for a person:
 * what somebody has is the file their company assigned them or {@code ssh-keygen} wrote, and
 * storing that was accepted with a warning about spaces and then failed at the moment it was used
 * with <em>illegal base64 character 2d</em> - an error about base64 for a problem about ssh keys.
 * Measured on 2026-09-19, with a key straight from {@code ssh-keygen -t ed25519}.
 * <p>
 * <strong>What is refused, and clearly.</strong> An encrypted key, because this cannot ask for its
 * passphrase and must not guess; and any algorithm that is not Ed25519, because the agent signs
 * with Ed25519 and a stored RSA key would fail later rather than here. Both say what to do next.
 */
public final class OpenSshPrivateKey {

    private static final String BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----";

    private static final String END = "-----END OPENSSH PRIVATE KEY-----";

    private static final byte[] MAGIC = "openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII);

    /** What an Ed25519 private key blob holds: the seed and then the public half. */
    private static final int SEED_LENGTH = 32;

    private OpenSshPrivateKey() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Tells whether this text looks like an OpenSSH private key file.
     *
     * @param text What somebody stored.
     * @return {@code true} when it carries the armour.
     */
    public static boolean looksLikeOne(final String text) {
        return text != null && text.contains(BEGIN);
    }

    /**
     * What a private key file is, without opening it.
     *
     * @param type The algorithm, such as {@code ssh-ed25519}, or "" when it cannot be read.
     * @param encrypted Whether a passphrase protects it. An encrypted key says nothing else about
     *        itself: its type is inside the part that is encrypted.
     */
    public record Described(String type, boolean encrypted) {

        /**
         * Tells whether this machine can sign with it as it stands.
         *
         * @return {@code true} for an unencrypted Ed25519 key.
         */
        public boolean usable() {
            return !encrypted && "ssh-ed25519".equals(type);
        }
    }

    /**
     * Tells whether this text is a private key of any kind.
     * <p>
     * <strong>Wider than {@link #looksLikeOne(String)} on purpose.</strong> That one knows
     * OpenSSH's armour, and an RSA key in PEM form says {@code -----BEGIN RSA PRIVATE KEY-----}
     * instead - so it was stored whole, without a word, and failed days later as a key that could
     * not be decoded. Measured on 2026-09-19: 1679 characters in a vault entry that every check
     * called present and ready.
     *
     * @param text What somebody stored.
     * @return {@code true} for any {@code BEGIN ... PRIVATE KEY} armour.
     */
    public static boolean looksLikeAnyPrivateKey(final String text) {
        return text != null && text.contains("PRIVATE KEY-----")
                && text.contains("-----BEGIN");
    }

    /**
     * Returns what kind of private key this is, for saying so.
     *
     * @param text The file.
     * @return The word in its armour - {@code OPENSSH}, {@code RSA}, {@code EC} - or "".
     */
    public static String armourOf(final String text) {
        final java.util.regex.Matcher found = java.util.regex.Pattern
                .compile("-----BEGIN ([A-Z0-9 ]*)PRIVATE KEY-----").matcher(text);
        return found.find() ? found.group(1).strip() : "";
    }

    /**
     * Says what a private key file is, without failing on one this cannot use.
     * <p>
     * Separate from {@link #seedBase64(String)} because listing what a machine has and storing one
     * of them are different questions: a list that threw on the first RSA key in somebody's
     * {@code ~/.ssh} would be a list nobody could see.
     *
     * @param text The whole file.
     * @return What it is, or {@code null} when it is not an OpenSSH private key at all.
     */
    public static @org.jspecify.annotations.Nullable Described describe(final String text) {
        if (!looksLikeOne(text)) {
            return null;
        }
        final int begins = text.indexOf(BEGIN);
        final int ends = text.indexOf(END);
        if (ends < begins) {
            return null;
        }
        try {
            final byte[] blob = Base64.getMimeDecoder().decode(
                    text.substring(begins + BEGIN.length(), ends));
            final Reader reader = new Reader(blob);
            reader.expect(MAGIC, "not an OpenSSH private key");
            final String cipher = reader.string();
            reader.string();
            reader.bytes();
            if (!"none".equals(cipher)) {
                // The type lives inside the encrypted part. Saying "" is the truth; guessing from
                // the public half would report a type this cannot confirm.
                return new Described("", true);
            }
            reader.int32();
            reader.bytes();
            final Reader secret = new Reader(reader.bytes());
            secret.int32();
            secret.int32();
            return new Described(secret.string(), false);
        } catch (final IllegalArgumentException | VaultException ex) {
            return null;
        }
    }

    /**
     * Returns the seed to store, given the contents of a private key file.
     *
     * @param text The whole file, armour and all.
     * @return Base64 of the 32-byte seed, which is what the vault holds.
     * @throws VaultException If it is not an unencrypted Ed25519 key, saying what to do.
     */
    public static String seedBase64(final String text) {
        final int begins = text.indexOf(BEGIN);
        final int ends = text.indexOf(END);
        if (begins < 0 || ends < begins) {
            throw new VaultException("This is not an OpenSSH private key file: no "
                    + "'BEGIN OPENSSH PRIVATE KEY' line. A public key (.pub) is not the one to"
                    + " store - this machine needs the private half.");
        }
        final byte[] blob;
        try {
            blob = Base64.getMimeDecoder().decode(
                    text.substring(begins + BEGIN.length(), ends));
        } catch (final IllegalArgumentException ex) {
            throw new VaultException("This key file is damaged: its body is not base64.");
        }
        final Reader reader = new Reader(blob);
        reader.expect(MAGIC, "This is not an OpenSSH private key file.");
        final String cipher = reader.string();
        reader.string();
        reader.bytes();
        if (!"none".equals(cipher)) {
            // A passphrase-protected key. Nothing here can ask for it - this runs in a daemon as
            // often as at a terminal - and guessing is not on the table.
            throw new VaultException("This key is protected by a passphrase, and this cannot ask"
                    + " for one. Store a copy without it: 'ssh-keygen -p -f <file>' with an empty"
                    + " new passphrase writes one. The vault is what keeps it secret from then on.");
        }
        if (reader.int32() != 1) {
            throw new VaultException("This file holds more than one key, which is not something"
                    + " this can choose between. Store a file with one key in it.");
        }
        reader.bytes();
        final Reader secret = new Reader(reader.bytes());
        secret.int32();
        secret.int32();
        final String type = secret.string();
        if (!"ssh-ed25519".equals(type)) {
            // RSA and ECDSA would be stored happily and fail when the agent signs, which is a
            // different day and a different error.
            throw new VaultException("This is an " + type + " key and this machine signs with"
                    + " Ed25519. Ask for an Ed25519 key, or make one with"
                    + " 'ssh-keygen -t ed25519' and have its public half authorised.");
        }
        secret.bytes();
        final byte[] privateHalf = secret.bytes();
        if (privateHalf.length < SEED_LENGTH) {
            throw new VaultException("This Ed25519 key is malformed: its private half is "
                    + privateHalf.length + " bytes.");
        }
        // The private blob is seed || public half. The seed is what the vault keeps.
        return Base64.getEncoder().encodeToString(
                java.util.Arrays.copyOfRange(privateHalf, 0, SEED_LENGTH));
    }

    /** Reads the length-prefixed fields an OpenSSH key is made of. */
    private static final class Reader {

        private final byte[] bytes;

        private int at;

        private Reader(final byte[] bytes) {
            this.bytes = bytes;
        }

        private void expect(final byte[] prefix, final String complaint) {
            if (bytes.length < prefix.length) {
                throw new VaultException(complaint);
            }
            for (int index = 0; index < prefix.length; index++) {
                if (bytes[index] != prefix[index]) {
                    throw new VaultException(complaint);
                }
            }
            at = prefix.length;
        }

        private int int32() {
            if (at + 4 > bytes.length) {
                throw new VaultException("This key file ends in the middle of a field.");
            }
            final int value = ((bytes[at] & 0xFF) << 24) | ((bytes[at + 1] & 0xFF) << 16)
                    | ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
            at += 4;
            return value;
        }

        private byte[] bytes() {
            final int length = int32();
            if (length < 0 || at + length > bytes.length) {
                throw new VaultException("This key file ends in the middle of a field.");
            }
            final byte[] value = java.util.Arrays.copyOfRange(bytes, at, at + length);
            at += length;
            return value;
        }

        private String string() {
            return new String(bytes(), StandardCharsets.US_ASCII);
        }
    }
}
