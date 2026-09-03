package org.fuin.sokar.supervisor;

/**
 * Turns the token an agent presented into the credential to send upstream.
 * <p>
 * An interface rather than a {@link org.fuin.sokar.vault.TokenBroker} parameter so the proxy can
 * be tested without a vault, and so the three outcomes are named. They are not
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
     */
    record Granted(String credential) implements Result {
    }

    /**
     * The token was not this task's, or has expired or been revoked. Answered as {@code 401} so
     * the agent treats it as a credential rejection rather than a transport fault.
     */
    record Rejected() implements Result {
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
