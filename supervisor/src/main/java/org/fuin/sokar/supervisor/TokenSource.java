package org.fuin.sokar.supervisor;

/**
 * Where the broker gets the token it attaches for a credential that is not a fixed value: one bought with a
 * client secret or a grant, or a sign-in renewed on the host.
 */
public interface TokenSource {

    /**
     * Returns a token good for at least a minute.
     *
     * @return The token.
     * @throws TokenPurchase.Refused When none could be had, said as the authorization server's.
     */
    String current() throws TokenPurchase.Refused;
}
