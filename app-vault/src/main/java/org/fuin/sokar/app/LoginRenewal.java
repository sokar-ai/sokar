package org.fuin.sokar.app;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.supervisor.TokenPurchase;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultFile;
import org.jspecify.annotations.Nullable;

/**
 * Keeps an agent's sign-in working past its access token's end, on the host, for the broker.
 * <p>
 * <strong>The stored token is used until shortly before it ends</strong>, so a task started right after a sign-in
 * renews nothing. Then the renewal happens <strong>inside one update of the vault</strong>, under its lock, which
 * spans processes: every running task has a broker of its own, the service rotates the refresh token on each
 * renewal, and two brokers renewing with the same one would spend it for each other. So whoever takes the lock
 * first renews and writes the new token, its end and the rotated refresh token back; whoever comes second finds a
 * token that is good and takes it.
 * <p>
 * The task never sees any of it: it holds its phantom token, and a renewal it asks for itself is refused by the
 * proxy.
 */
final class LoginRenewal implements org.fuin.sokar.supervisor.TokenSource {

    private final String name;

    private final VaultFile vault;

    private final VaultFile.Opener opener;

    private final java.net.http.HttpClient http;

    private final Clock clock;

    private @Nullable String token;

    private Instant good;

    /**
     * Constructor, starting from what the vault holds.
     *
     * @param name The credential.
     * @param entry Its entry: the access token, when it ends, where and as whom it is renewed.
     * @param vault The vault it is kept in.
     * @param opener What opens it, for writing a renewal back.
     * @param http How the service is reached.
     * @param clock The time.
     */
    LoginRenewal(final String name, final VaultEntry entry, final VaultFile vault, final VaultFile.Opener opener,
            final java.net.http.HttpClient http, final Clock clock) {
        this.name = name;
        this.vault = vault;
        this.opener = opener;
        this.http = http;
        this.clock = clock;
        this.token = entry.value();
        this.good = goodUntil(entry);
    }

    @Override
    public synchronized String current() throws TokenPurchase.Refused {
        if (token != null && clock.instant().isBefore(good)) {
            return token;
        }
        final TokenPurchase.Refused[] refused = new TokenPurchase.Refused[1];
        try {
            renew(refused);
        } catch (final org.fuin.sokar.vault.VaultException ex) {
            throw new TokenPurchase.Refused("the sign-in for '" + name + "' could not be renewed: " + ex.getMessage(),
                    ex);
        }
        if (refused[0] != null) {
            throw refused[0];
        }
        return java.util.Objects.requireNonNull(token);
    }

    private void renew(final TokenPurchase.Refused[] refused) {
        vault.update(opener, entries -> {
            final VaultEntry entry = entries.get(name);
            final VaultEntry refresh = entries.get(TaskSecrets.GRANT_PREFIX + name);
            if (entry == null) {
                refused[0] = new TokenPurchase.Refused("the vault no longer holds '" + name + "'");
                return entries;
            }
            if (clock.instant().isBefore(goodUntil(entry))) {
                // Renewed by another task's broker while this one waited for the lock: its token is good.
                token = entry.value();
                good = goodUntil(entry);
                return entries;
            }
            if (refresh == null || !LoginCredentials.renewable(entry)) {
                refused[0] = new TokenPurchase.Ended("the sign-in for '" + name + "' has ended and nothing here can"
                        + " renew it; sign in again with 'sokar vault login'");
                return entries;
            }
            final String[] rotated = { refresh.value() };
            final TokenPurchase purchase = new TokenPurchase(new TokenPurchase.Refresh(
                    java.util.Objects.requireNonNull(entry.settings().get(LoginCredentials.TOKEN_URL)),
                    java.util.Objects.requireNonNull(entry.settings().get(LoginCredentials.CLIENT_ID)),
                    null, refresh.value(), next -> rotated[0] = next), http, clock);
            final String renewed;
            try {
                renewed = purchase.current();
            } catch (final TokenPurchase.Ended ex) {
                refused[0] = new TokenPurchase.Ended("the sign-in for '" + name + "' was revoked or has expired at the"
                        + " service; sign in again with 'sokar vault login'");
                return entries;
            } catch (final TokenPurchase.Refused ex) {
                refused[0] = ex;
                return entries;
            }
            final Map<String, String> settings = new LinkedHashMap<>(entry.settings());
            settings.put(LoginCredentials.EXPIRES_AT, purchase.goodUntil().plus(TokenPurchase.MARGIN).toString());
            entries.put(name, new VaultEntry(renewed, entry.type(), settings));
            entries.put(TaskSecrets.GRANT_PREFIX + name, new VaultEntry(rotated[0], refresh.type(), refresh.settings()));
            token = renewed;
            good = purchase.goodUntil();
            return entries;
        });
    }

    /**
     * Returns until when an entry's token is used: a margin before it ends, or never past now when its end is not
     * known - a sign-in from before the end was kept is renewed at its first use.
     */
    private Instant goodUntil(final VaultEntry entry) {
        final String ends = entry.settings().get(LoginCredentials.EXPIRES_AT);
        if (ends == null) {
            return Instant.MIN;
        }
        try {
            return Instant.parse(ends).minus(TokenPurchase.MARGIN);
        } catch (final java.time.format.DateTimeParseException ex) {
            return Instant.MIN;
        }
    }
}
