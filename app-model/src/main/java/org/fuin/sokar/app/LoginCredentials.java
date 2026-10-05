package org.fuin.sokar.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.agent.api.Credential;
import org.fuin.sokar.vault.VaultEntry;

/**
 * Keeps what an agent's own sign-in left, as {@code vault login} and {@code vault import} both store it.
 * <p>
 * <strong>Everything the agent handed over, not only the token.</strong> Both used to keep the value and its
 * type and drop the rest, so a subscription's refresh token never reached the vault and the credential died
 * with its access token, hours after the sign-in. Now:
 * <ul>
 * <li>the access token is the entry, as before;</li>
 * <li>when it ends, where it is renewed and the client it was granted to are the entry's settings
 *     ({@value #EXPIRES_AT}, {@value #TOKEN_URL}, {@value #CLIENT_ID}) - none of them a secret, so
 *     {@code vault list} may show them;</li>
 * <li>the refresh token is kept hidden beside it, as {@code vault authorize} keeps a grant, and the broker
 *     renews with it on the host ({@link LoginRenewal}).</li>
 * </ul>
 * <strong>Only for a sign-in that is Sokar's alone.</strong> {@code vault login} signs in in a throwaway container,
 * so nobody else holds that refresh token. {@code vault import} copies what the agent on this machine keeps using:
 * renewing that copy would spend the refresh token the developer's own session renews with - the service rotates
 * it - and end that session. An import keeps the expiry and is never renewed.
 */
final class LoginCredentials {

    /** The setting saying when the access token ends, as an instant. */
    static final String EXPIRES_AT = "expires_at";

    /** The setting naming the service's token endpoint. */
    static final String TOKEN_URL = "token_url";

    /** The setting naming the client the sign-in was granted to. */
    static final String CLIENT_ID = "client_id";

    /** The type of the hidden companion holding the refresh token. */
    static final String REFRESH = "refresh-token";

    private LoginCredentials() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Puts a credential and what renews it into the vault's entries, replacing what the name held.
     *
     * @param entries The vault's entries, changed in place.
     * @param key The name it is kept under.
     * @param value What the agent's sign-in left.
     * @param own Whether the sign-in is Sokar's alone, so that the broker may renew it.
     */
    static void put(final Map<String, VaultEntry> entries, final String key, final Credential value,
            final boolean own) {
        final Map<String, String> settings = new LinkedHashMap<>();
        final String expires = value.attributes().get(Credential.EXPIRES_AT);
        if (expires != null && expires.matches("\\d{1,15}")) {
            settings.put(EXPIRES_AT, java.time.Instant.ofEpochMilli(Long.parseLong(expires)).toString());
        }
        final String refresh = value.attributes().get(Credential.REFRESH_TOKEN);
        final String url = value.attributes().get(Credential.TOKEN_URL);
        final String client = value.attributes().get(Credential.CLIENT_ID);
        final boolean renewable = own && refresh != null && !refresh.isBlank() && url != null && client != null
                && org.fuin.sokar.supervisor.Grants.secure(url);
        if (renewable) {
            settings.put(TOKEN_URL, url);
            settings.put(CLIENT_ID, client);
        }
        entries.put(key, new VaultEntry(value.secret(), value.type(), settings));
        if (renewable) {
            entries.put(TaskSecrets.GRANT_PREFIX + key, new VaultEntry(java.util.Objects.requireNonNull(refresh), REFRESH));
        } else {
            // A sign-in with nothing to renew it replaces one that had: the old refresh token is not this one's.
            entries.remove(TaskSecrets.GRANT_PREFIX + key);
        }
    }

    /**
     * Whether an entry is a sign-in the broker can renew: it names where and as whom.
     *
     * @param entry The entry.
     * @return Whether it can be renewed, given its refresh token.
     */
    static boolean renewable(final VaultEntry entry) {
        return entry.settings().containsKey(TOKEN_URL) && entry.settings().containsKey(CLIENT_ID);
    }
}
