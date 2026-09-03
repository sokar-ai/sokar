package org.fuin.sokar.vault;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

/**
 * A credential that stands in for a real one and is worth nothing without Sokar.
 * <p>
 * The agent is given this instead of the provider's token. It is accepted only by Sokar's own
 * broker, only for the scope it was minted for, and only until the task ends. An agent that leaks
 * one has leaked something that stops working the moment the container does.
 *
 * @param value The token as the agent sees it.
 * @param scope What the token may be exchanged for, usually a provider name.
 * @param subject Which task it was minted for.
 * @param expiresAt When it stops being accepted.
 */
public record PhantomToken(String value, String scope, String subject, Instant expiresAt) {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Prefix, so a leaked token is recognisable as Sokar's and not a real provider credential. */
    public static final String PREFIX = "sokar_pt_";

    /**
     * Mints a token.
     *
     * @param scope What it may be exchanged for.
     * @param subject Which task it is for.
     * @param expiresAt When it stops being accepted.
     * @return A new token.
     */
    public static PhantomToken mint(String scope, String subject, Instant expiresAt) {
        final byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return new PhantomToken(
                PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                scope, subject, expiresAt);
    }

    /**
     * Tells whether the token is still valid at a point in time.
     *
     * @param now The current time.
     * @return {@code true} if it has not expired.
     */
    public boolean validAt(Instant now) {
        return now.isBefore(expiresAt);
    }

    /**
     * Compares the token value with a presented one, in constant time.
     *
     * @param presented What a client sent.
     * @return {@code true} if they match.
     */
    public boolean matches(String presented) {
        return MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Shortens a token value for printing.
     * <p>
     * Shared with {@link #toString()} so there is one rule rather than two: a token that is
     * printed whole makes any log a usable credential for the token's lifetime, and that has to
     * be true everywhere, not only where a {@code PhantomToken} object happens to be at hand.
     *
     * @param value Token value.
     * @return Enough to recognise it by, and not enough to use.
     */
    public static String abbreviate(String value) {
        return value.substring(0, Math.min(PREFIX.length() + 4, value.length())) + "...";
    }

    @Override
    public String toString() {
        // These end up in logs and exception messages by accident.
        return "PhantomToken[" + scope + "/" + subject + " " + abbreviate(value) + "]";
    }
}
