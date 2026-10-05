package org.fuin.sokar.vault;

/**
 * A key the agent can offer and sign with.
 * <p>
 * <strong>Why this exists.</strong> The vault held one kind of key - an Ed25519 seed - and the
 * agent knew one way to sign. That is the better default and it is not everybody's key: the
 * operator's daily key for git is RSA, and a vault that takes only the keys we like is a vault
 * people work around, which puts their secrets back in a file beside it. So the agent takes keys
 * rather than one key.
 */
public interface AgentKey {

    /** Signature flag: the client wants SHA-256 rather than SHA-1. */
    int RSA_SHA2_256 = 2;

    /** Signature flag: the client wants SHA-512 rather than SHA-1. */
    int RSA_SHA2_512 = 4;

    /**
     * Returns the comment, as {@code ssh-add -l} shows it.
     *
     * @return The comment.
     */
    String comment();

    /**
     * Returns the OpenSSH public key blob.
     *
     * @return The blob, which is what a client matches a sign request against.
     */
    byte[] keyBlob();

    /**
     * Returns the key in {@code authorized_keys} form.
     *
     * @return One line, without a trailing newline.
     */
    String authorizedKeysLine();

    /**
     * Signs data the way the client asked.
     *
     * @param data What to sign.
     * @param flags What the client sent. For RSA these choose the hash, and choosing wrongly is
     *        not a local matter: GitHub stopped accepting SHA-1 signatures in 2022, so an agent
     *        that ignores them answers with something the forge rejects for reasons it does not
     *        explain.
     * @return The OpenSSH signature blob, naming the algorithm actually used.
     */
    byte[] sign(byte[] data, int flags);
}
