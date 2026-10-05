package org.fuin.sokar.vault;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.util.Base64;

/**
 * Reads what a vault entry holds into a key the agent can sign with.
 * <p>
 * <strong>Two shapes, because there were two eras.</strong> An entry written before this held
 * base64 of a 32-byte Ed25519 seed and nothing else; one written now holds the key file itself,
 * whatever kind it is. Both are read, so nothing anybody has stored has to be stored again - and
 * the RSA key that was already sitting in a vault, rejected as undecodable, starts working.
 * <p>
 * <strong>No new dependency for RSA.</strong> A PKCS#8 key is what the JDK's own KeyFactory
 * takes; a PKCS#1 key is a sequence of nine integers, which is little enough to read here. Adding
 * a PEM library for it would have put a second parser of somebody else's format into a native
 * image for the sake of thirty lines.
 */
public final class StoredKey {

    private StoredKey() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the key a vault entry holds.
     *
     * @param value What the vault stored.
     * @param comment What to call it.
     * @return Something the agent can offer and sign with.
     * @throws VaultException If it is not a key this can use, saying which it is.
     */
    public static AgentKey of(final String value, final String comment) {
        final String text = value.strip();
        if (OpenSshPrivateKey.looksLikeAnyPrivateKey(text)) {
            return fromFile(text, comment);
        }
        try {
            return new SigningKey(Base64.getDecoder().decode(text), comment);
        } catch (final IllegalArgumentException ex) {
            throw new VaultException("'" + comment + "' does not hold a key this machine can"
                    + " read: it is neither a private key file nor an Ed25519 seed.");
        }
    }

    /**
     * Returns what to store in the vault for a key file.
     * <p>
     * An Ed25519 key keeps its seed, which is what every entry written until now holds; anything
     * else is kept as the file, because there is nothing smaller that is still the key.
     *
     * @param text The key file.
     * @return What to put in the vault.
     */
    public static String toStore(final String text) {
        if (OpenSshPrivateKey.looksLikeOne(text)) {
            final OpenSshPrivateKey.Described described = OpenSshPrivateKey.describe(text);
            if (described != null && described.usable()) {
                return OpenSshPrivateKey.seedBase64(text);
            }
        }
        return text.strip();
    }

    private static AgentKey fromFile(final String text, final String comment) {
        if (OpenSshPrivateKey.looksLikeOne(text)) {
            final OpenSshPrivateKey.Described described = OpenSshPrivateKey.describe(text);
            if (described != null && described.encrypted()) {
                throw new VaultException("'" + comment + "' is protected by a passphrase, and"
                        + " nothing here can ask for one.");
            }
            if (described != null && described.usable()) {
                return new SigningKey(
                        Base64.getDecoder().decode(OpenSshPrivateKey.seedBase64(text)), comment);
            }
            // An OpenSSH-wrapped RSA key. 'ssh-keygen -p -m PEM' converts it in one command, and
            // saying so is better than a second parser of somebody else's container format.
            throw new VaultException("'" + comment + "' is an OpenSSH-wrapped key that is not"
                    + " Ed25519. Convert it with 'ssh-keygen -p -m PEM -f <file>' and store it"
                    + " again, or use it where it lies with --file.");
        }
        final String armour = OpenSshPrivateKey.armourOf(text);
        try {
            final byte[] der = Base64.getMimeDecoder().decode(body(text));
            final RSAPrivateCrtKey key = "RSA".equals(armour)
                    ? pkcs1(der)
                    : (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                            .generatePrivate(new PKCS8EncodedKeySpec(der));
            return new RsaKey(key, key.getModulus(), key.getPublicExponent(), comment);
        } catch (final ClassCastException ex) {
            throw new VaultException("'" + comment + "' is not an RSA key, and this machine signs"
                    + " with Ed25519 and RSA.");
        } catch (final java.security.GeneralSecurityException | IllegalArgumentException ex) {
            throw new VaultException("'" + comment + "' could not be read as a private key: "
                    + ex.getMessage());
        }
    }

    /**
     * Returns an RSA key from PKCS#1, which is a sequence of nine integers.
     *
     * @param der The decoded body.
     * @return The key.
     * @throws java.security.GeneralSecurityException If the numbers do not make a key.
     */
    private static RSAPrivateCrtKey pkcs1(final byte[] der)
            throws java.security.GeneralSecurityException {
        final Der reader = new Der(der);
        reader.sequence();
        reader.integer();
        final BigInteger modulus = reader.integer();
        final BigInteger publicExponent = reader.integer();
        final BigInteger privateExponent = reader.integer();
        final BigInteger primeP = reader.integer();
        final BigInteger primeQ = reader.integer();
        final BigInteger exponentP = reader.integer();
        final BigInteger exponentQ = reader.integer();
        final BigInteger coefficient = reader.integer();
        return (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(
                new RSAPrivateCrtKeySpec(modulus, publicExponent, privateExponent, primeP,
                        primeQ, exponentP, exponentQ, coefficient));
    }

    private static String body(final String text) {
        final int begins = text.indexOf("-----", text.indexOf("-----BEGIN") + 10) + 5;
        final int ends = text.lastIndexOf("-----END");
        if (begins <= 4 || ends < begins) {
            throw new VaultException("this key file has no body between its armour lines.");
        }
        return text.substring(begins, ends);
    }

    /** Just enough DER to read a sequence of integers, and nothing else. */
    private static final class Der {

        private final byte[] bytes;

        private int at;

        private Der(final byte[] bytes) {
            this.bytes = bytes;
        }

        private void sequence() {
            expect(0x30);
            length();
        }

        private BigInteger integer() {
            expect(0x02);
            final int length = length();
            if (length > bytes.length - at) {
                // Copied anyway it is padded with zeros, and a key with a wrong number signs nothing anyone accepts.
                throw new VaultException("this key file ends inside one of its numbers.");
            }
            final BigInteger value = new BigInteger(1,
                    java.util.Arrays.copyOfRange(bytes, at, at + length));
            at += length;
            return value;
        }

        private void expect(final int tag) {
            if (at >= bytes.length || (bytes[at] & 0xFF) != tag) {
                throw new VaultException("this is not the key file it claims to be.");
            }
            at++;
        }

        private int length() {
            if (at >= bytes.length) {
                throw new VaultException("this key file ends where a length should be.");
            }
            int length = bytes[at++] & 0xFF;
            if ((length & 0x80) != 0) {
                final int count = length & 0x7F;
                if (count == 0 || count > 3 || count > bytes.length - at) {
                    // Three bytes say sixteen megabytes, far beyond any key; more is a file that is not one.
                    throw new VaultException("this key file states a length it cannot have.");
                }
                length = 0;
                for (int index = 0; index < count; index++) {
                    length = (length << 8) | (bytes[at++] & 0xFF);
                }
            }
            return length;
        }
    }
}
