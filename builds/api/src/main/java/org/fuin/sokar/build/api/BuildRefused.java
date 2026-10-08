package org.fuin.sokar.build.api;

import java.io.Serial;

/**
 * The forge would not say anything about the build, for a reason Sokar acts on.
 * <p>
 * Distinct from a build without a verdict ({@link Build#UNKNOWN}): a rate limit is waited out, a refused credential
 * stops the watch until a person replaces it, and a repository the credential cannot see is a project file to fix.
 * Each reaches the task as what it is, never as a failed build.
 */
public final class BuildRefused extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Why the forge would not answer. */
    public enum Reason {

        /** The forge limits how often it is asked, and the limit is reached. */
        RATE_LIMITED("RateLimited"),

        /** The forge refused the credential: expired, revoked, wrong, or without the scope it needs. */
        CREDENTIAL_REFUSED("CredentialRefused"),

        /** The forge knows no such repository, or the credential may not see it. */
        NO_SUCH_REPOSITORY("NoSuchRepository"),

        /** The forge did not answer at all, or answered something that is not an answer. */
        UNREACHABLE("Unreachable");

        private final String wire;

        Reason(final String wire) {
            this.wire = wire;
        }

        /**
         * Returns the error's name on the wire, without the interface.
         *
         * @return It.
         */
        public String wire() {
            return wire;
        }

        /**
         * Returns the reason a wire name stands for.
         *
         * @param wire A fully qualified error name.
         * @return It, or {@code null} when the name is none of these.
         */
        static @org.jspecify.annotations.Nullable Reason of(final @org.jspecify.annotations.Nullable String wire) {
            for (final Reason reason : values()) {
                if (BuildProtocol.error(reason).equals(wire)) {
                    return reason;
                }
            }
            return null;
        }
    }

    private final Reason reason;

    private final long retryAfterSeconds;

    /**
     * Constructor.
     *
     * @param reason Why.
     * @param detail What the forge said, for a person and the task.
     * @param retryAfterSeconds When asking again can succeed, for {@link Reason#RATE_LIMITED}; 0 when unknown.
     */
    public BuildRefused(final Reason reason, final String detail, final long retryAfterSeconds) {
        super(detail);
        this.reason = reason;
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
    }

    /**
     * Constructor without a time to retry.
     *
     * @param reason Why.
     * @param detail What the forge said.
     */
    public BuildRefused(final Reason reason, final String detail) {
        this(reason, detail, 0);
    }

    /**
     * Returns why.
     *
     * @return The reason.
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns what the forge said.
     *
     * @return The detail.
     */
    public String detail() {
        return String.valueOf(getMessage());
    }

    /**
     * Returns when asking again can succeed.
     *
     * @return Seconds; 0 when the forge did not say.
     */
    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
