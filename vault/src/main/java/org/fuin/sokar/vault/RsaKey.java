package org.fuin.sokar.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Base64;

/**
 * An RSA key that signs on the host, and never leaves it.
 * <p>
 * <strong>Why RSA is here at all.</strong> Ed25519 is the better default and this machine's own
 * keys are Ed25519 - but the key somebody already uses every day for git is frequently RSA, and a
 * vault that refuses it is a vault they keep their key beside instead of in.
 * <p>
 * <strong>The hash is the client's choice, and getting it wrong is silent.</strong> An ssh agent
 * is asked to sign with flags that say SHA-256 or SHA-512; plain {@code ssh-rsa} means SHA-1,
 * which GitHub stopped accepting in 2022. An agent that ignores the flags therefore answers with a
 * signature the forge rejects, and the forge does not say why. So the flags are read, and the
 * algorithm the blob names is the one that was used.
 */
public final class RsaKey implements AgentKey {

    private final java.security.interfaces.RSAPrivateKey privateKey;

    private final BigInteger modulus;

    private final BigInteger exponent;

    private final String comment;

    /**
     * Constructor.
     *
     * @param privateKey The private half.
     * @param modulus Its modulus, for the public blob.
     * @param exponent Its public exponent, for the public blob.
     * @param comment What to call it.
     */
    RsaKey(final java.security.interfaces.RSAPrivateKey privateKey, final BigInteger modulus,
            final BigInteger exponent, final String comment) {
        this.privateKey = privateKey;
        this.modulus = modulus;
        this.exponent = exponent;
        this.comment = comment;
    }

    @Override
    public String comment() {
        return comment;
    }

    @Override
    public byte[] keyBlob() {
        // "ssh-rsa", then e, then n - in that order, which is not the order they are usually
        // written or spoken about.
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            SshWire.writeString(out, "ssh-rsa");
            SshWire.writeString(out, exponent.toByteArray());
            SshWire.writeString(out, modulus.toByteArray());
            return out.toByteArray();
        } catch (final IOException ex) {
            throw new VaultException("cannot build the public key: " + ex.getMessage());
        }
    }

    @Override
    public String authorizedKeysLine() {
        return "ssh-rsa " + Base64.getEncoder().encodeToString(keyBlob()) + " " + comment;
    }

    @Override
    public byte[] sign(final byte[] data, final int flags) {
        final String algorithm = algorithmFor(flags);
        try {
            final java.security.Signature signature =
                    java.security.Signature.getInstance(javaNameOf(algorithm));
            signature.initSign(privateKey);
            signature.update(data);
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            // The blob names what was used, not what the key is. A client that asked for
            // rsa-sha2-512 and is told "ssh-rsa" treats the answer as SHA-1 and fails at the
            // forge rather than here.
            SshWire.writeString(out, algorithm);
            SshWire.writeString(out, signature.sign());
            return out.toByteArray();
        } catch (final java.security.GeneralSecurityException | IOException ex) {
            throw new VaultException("cannot sign with '" + comment + "': " + ex.getMessage());
        }
    }

    /**
     * Returns the algorithm name for what the client asked for.
     *
     * @param flags What the client sent.
     * @return The ssh algorithm name.
     */
    static String algorithmFor(final int flags) {
        if ((flags & RSA_SHA2_512) != 0) {
            return "rsa-sha2-512";
        }
        if ((flags & RSA_SHA2_256) != 0) {
            return "rsa-sha2-256";
        }
        // No flags means the client will take SHA-1. Modern ssh always asks for one of the two
        // above; anything that does not is old enough that answering it honestly is right.
        return "ssh-rsa";
    }

    private static String javaNameOf(final String algorithm) {
        return switch (algorithm) {
            case "rsa-sha2-512" -> "SHA512withRSA";
            case "rsa-sha2-256" -> "SHA256withRSA";
            default -> "SHA1withRSA";
        };
    }
}
