package org.fuin.sokar.vault;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Exchanges phantom tokens for real credentials, without ever handing the real one out.
 * <p>
 * The agent holds a phantom token. When it makes a request, Sokar - not the agent - replaces the
 * phantom with the real credential on the way out. The container never sees the real value, which
 * is what makes a leak from inside it survivable: the phantom stops working when the task ends,
 * and the real credential was never there to leak.
 * <p>
 * <strong>The real credential is fetched per exchange and not cached.</strong> Holding it would
 * put it in this process's memory for the life of the task rather than for the length of one
 * request, for no benefit: the vault read is already cheap once unlocked.
 */
public class TokenBroker {

    private final Supplier<Map<String, String>> credentials;

    private final Map<String, PhantomToken> issued = new ConcurrentHashMap<>();

    /**
     * Constructor.
     *
     * @param credentials Returns the real credentials, keyed by scope.
     */
    public TokenBroker(Supplier<Map<String, String>> credentials) {
        this.credentials = credentials;
    }

    /**
     * Mints a phantom token for a task.
     *
     * @param scope Provider the token stands in for.
     * @param subject Task the token is for.
     * @param lifetime How long it is accepted.
     * @return The token to give to the agent.
     * @throws VaultException If no real credential exists for the scope.
     */
    public PhantomToken mint(String scope, String subject, Duration lifetime) {
        if (!credentials.get().containsKey(scope)) {
            // Minting a token that can never be exchanged would fail later, inside the agent,
            // as an authentication error against the provider - which is the hardest possible
            // place to work out what went wrong.
            throw new VaultException("The vault has no credential for scope '" + scope + "'");
        }
        final PhantomToken token = PhantomToken.mint(scope, subject, Instant.now().plus(lifetime));
        issued.put(token.value(), token);
        return token;
    }

    /**
     * Takes over a token that was issued to a task before, so a resumed task keeps working.
     * <p>
     * A container's environment is fixed when it is created, so the token it holds cannot be
     * changed afterwards. Minting a new one for a resumed task would leave the agent presenting a
     * value this broker has never seen, which reads as a rejected credential rather than as what
     * it is. The lifetime starts again: the task is continuing, not being extended past a
     * decision anyone made about it.
     *
     * @param value The token the task already holds.
     * @param scope Agent the token is for.
     * @param subject Task the token is for.
     * @param lifetime How long it is accepted from now.
     * @return The adopted token.
     * @throws VaultException If no real credential exists for the scope.
     */
    public PhantomToken adopt(String value, String scope, String subject, Duration lifetime) {
        if (!credentials.get().containsKey(scope)) {
            throw new VaultException("The vault has no credential for scope '" + scope + "'");
        }
        if (!value.startsWith(PhantomToken.PREFIX)) {
            throw new VaultException("Not a phantom token");
        }
        final PhantomToken token =
                new PhantomToken(value, scope, subject, Instant.now().plus(lifetime));
        issued.put(token.value(), token);
        return token;
    }

    /**
     * Exchanges a phantom token for the real credential.
     *
     * @param presented What the client sent.
     * @param now The current time.
     * @return The real credential, or empty if the token is unknown, expired or revoked.
     */
    public Optional<String> exchange(String presented, Instant now) {
        final PhantomToken token = issued.get(presented);
        if (token == null || !token.validAt(now)) {
            return Optional.empty();
        }
        // Looked up by value rather than compared one by one: the map lookup is the fast path, and
        // the token's own constant-time compare guards the value that was found.
        if (!token.matches(presented)) {
            return Optional.empty();
        }
        return Optional.ofNullable(credentials.get().get(token.scope()));
    }

    /**
     * Revokes a token.
     *
     * @param value The token value.
     * @return {@code true} if it was known.
     */
    public boolean revoke(String value) {
        return issued.remove(value) != null;
    }

    /**
     * Revokes every token minted for a task. Called when a container goes away.
     *
     * @param subject The task.
     * @return How many were revoked.
     */
    public int revokeAllFor(String subject) {
        final List<String> toRemove = issued.values().stream()
                .filter(token -> token.subject().equals(subject))
                .map(PhantomToken::value)
                .toList();
        toRemove.forEach(issued::remove);
        return toRemove.size();
    }

    /**
     * Removes tokens that have expired.
     *
     * @param now The current time.
     * @return How many were removed.
     */
    public int expire(Instant now) {
        final List<String> stale = issued.values().stream()
                .filter(token -> !token.validAt(now))
                .map(PhantomToken::value)
                .toList();
        stale.forEach(issued::remove);
        return stale.size();
    }

    /**
     * Returns how many tokens are outstanding.
     *
     * @return Token count.
     */
    public int outstanding() {
        return issued.size();
    }
}
