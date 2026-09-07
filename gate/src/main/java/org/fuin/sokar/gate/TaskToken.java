package org.fuin.sokar.gate;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * A credential that is worth nothing outside this machine and outside this task.
 * <p>
 * The agent gets one of these instead of the operator's git credentials. It authenticates only to
 * the local gate, only for one task, and it dies with the container. An agent that leaks it has
 * leaked the ability to push into a mirror that nothing reads without review.
 *
 * @param value The token itself.
 */
public record TaskToken(String value) {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Mints a token.
     *
     * @return A new random token.
     */
    public static TaskToken mint() {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new TaskToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    /**
     * Compares this token with another in constant time.
     * <p>
     * String equality returns as soon as two bytes differ, which leaks the length of the matching
     * prefix. Over a local socket that is a thin channel, but it is free to close.
     *
     * @param other The value presented by a client.
     * @return {@code true} if they match.
     */
    public boolean matches(String other) {
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                other.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the value for an HTTP basic-authentication header.
     *
     * @return Header value.
     */
    public String basicAuthorization() {
        return "Basic " + Base64.getEncoder().encodeToString(
                ("sokar:" + value).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns enough of the token to recognize it, and not enough to push with it.
     * <p>
     * A log is the one place a token gets away with being written down: it is streamed to
     * whatever is tailing it, read by whoever is debugging, and pasted into bug reports. Printing
     * it in full defeated {@link #toString()}, which exists for exactly that reason.
     *
     * @return The first characters, followed by an ellipsis.
     */
    public String abbreviate() {
        return value.substring(0, Math.min(4, value.length())) + "...";
    }

    @Override
    public String toString() {
        // Tokens end up in log lines and exception messages by accident. This makes that harmless.
        return "TaskToken[" + abbreviate() + "]";
    }
}
