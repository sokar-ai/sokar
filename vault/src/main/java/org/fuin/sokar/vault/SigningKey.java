package org.fuin.sokar.vault;

import java.util.Base64;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

/**
 * An Ed25519 key that signs on the host, and never leaves it.
 * <p>
 * The private half is held here, in the {@code sokar} process. What crosses into the container is
 * a socket, so the container can ask for a signature but cannot obtain the key that produces it.
 * That distinction is the whole point of the vault: an agent that is compromised can use the key
 * while Sokar is running, and has nothing afterwards.
 */
public class SigningKey {

    private final Ed25519PrivateKeyParameters privateKey;

    private final Ed25519PublicKeyParameters publicKey;

    private final String comment;

    /**
     * Constructor from raw private key bytes.
     *
     * @param seed The 32-byte private key seed.
     * @param comment Comment shown by {@code ssh-add -l}.
     */
    public SigningKey(byte[] seed, String comment) {
        if (seed.length != Ed25519PrivateKeyParameters.KEY_SIZE) {
            throw new VaultException("An Ed25519 seed is "
                    + Ed25519PrivateKeyParameters.KEY_SIZE + " bytes, got " + seed.length);
        }
        this.privateKey = new Ed25519PrivateKeyParameters(seed, 0);
        this.publicKey = privateKey.generatePublicKey();
        this.comment = comment;
    }

    /**
     * Generates a new key.
     *
     * @param comment Comment shown by {@code ssh-add -l}.
     * @return New key.
     */
    public static SigningKey generate(String comment) {
        final byte[] seed = new byte[Ed25519PrivateKeyParameters.KEY_SIZE];
        new java.security.SecureRandom().nextBytes(seed);
        return new SigningKey(seed, comment);
    }

    /**
     * Returns the private key seed, for storing in the vault.
     *
     * @return Base64 of the 32-byte seed.
     */
    public String seedBase64() {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }

    /**
     * Returns the comment.
     *
     * @return Comment.
     */
    public String comment() {
        return comment;
    }

    /**
     * Returns the OpenSSH public key blob.
     *
     * @return Key blob.
     */
    public byte[] keyBlob() {
        return SshWire.ed25519KeyBlob(publicKey.getEncoded());
    }

    /**
     * Returns the key in {@code authorized_keys} form.
     *
     * @return One line, without a trailing newline.
     */
    public String authorizedKeysLine() {
        return "ssh-ed25519 " + Base64.getEncoder().encodeToString(keyBlob()) + " " + comment;
    }

    /**
     * Signs data.
     *
     * @param data What to sign.
     * @return The OpenSSH signature blob.
     */
    public byte[] sign(byte[] data) {
        final Ed25519Signer signer = new Ed25519Signer();
        signer.init(true, privateKey);
        signer.update(data, 0, data.length);
        return SshWire.ed25519SignatureBlob(signer.generateSignature());
    }

    /**
     * Verifies a signature produced by {@link #sign(byte[])}.
     *
     * @param data What was signed.
     * @param signatureBlob The blob returned by {@link #sign(byte[])}.
     * @return {@code true} if the signature is this key's.
     */
    public boolean verify(byte[] data, byte[] signatureBlob) {
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(signatureBlob);
        final byte[] algorithm = SshWire.readString(buffer);
        if (!"ssh-ed25519".equals(new String(algorithm, java.nio.charset.StandardCharsets.UTF_8))) {
            return false;
        }
        final byte[] signature = SshWire.readString(buffer);
        final Ed25519Signer verifier = new Ed25519Signer();
        verifier.init(false, publicKey);
        verifier.update(data, 0, data.length);
        return verifier.verifySignature(signature);
    }
}
