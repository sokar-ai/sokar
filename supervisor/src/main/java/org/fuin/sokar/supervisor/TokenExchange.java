package org.fuin.sokar.supervisor;

/**
 * Turns the token an agent presented into the credential to send upstream.
 * <p>
 * An interface rather than a {@link org.fuin.sokar.vault.TokenBroker} parameter so the proxy can
 * be tested without a vault, and so the outcomes are named. They are not
 * interchangeable: the operator's fix differs for each, and collapsing them into "empty" is what
 * makes a credential problem hard to diagnose.
 */
public interface TokenExchange {

    /**
     * The result of presenting a token.
     */
    sealed interface Result {
    }

    /**
     * The token was accepted.
     *
     * @param credential The real credential to send upstream.
     * @param scope Which credential the token stands for, which picks where the request goes when a task
     *        holds more than one; {@code null} for the one route a broker had before.
     */
    record Granted(String credential, @org.jspecify.annotations.Nullable String scope)
            implements Result {

        /**
         * A grant for the one route, as every grant was before a task held more than one credential.
         *
         * @param credential The real credential.
         */
        public Granted(String credential) {
            this(credential, null);
        }
    }

    /**
     * The token was not this task's, or has been revoked. Answered as {@code 401} so the agent
     * treats it as a credential rejection rather than a transport fault.
     */
    record Rejected() implements Result {
    }

    /**
     * The token was this task's own and its lifetime ran out.
     * <p>
     * Kept apart from {@link Rejected} because the fix is different and the agent's report is
     * otherwise indistinguishable from a wrong credential: the task simply outlived
     * {@code --token-hours}, and resuming it issues the same token again with a fresh lifetime.
     *
     * @param when The moment it stopped being accepted.
     */
    record Expired(java.time.Instant when) implements Result {
    }

    /**
     * The token was fine but the credential behind it could not be produced - the vault was
     * locked or the entry removed while the task was running. Answered as {@code 503}, because
     * retrying may work and the operator's fix is to unlock or restore, not to restart the task.
     *
     * @param reason What could not be done.
     */
    record Unavailable(String reason) implements Result {
    }

    /**
     * Exchanges a presented token.
     *
     * @param presented Token as it arrived, with any scheme prefix already stripped.
     * @return What to do with the request.
     */
    Result exchange(String presented);
}
