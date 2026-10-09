package org.fuin.sokar.agent.api;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The OAuth app an agent signs in to a provider with, as the agent itself does without Sokar.
 * <p>
 * Sokar ships no client id of its own: {@code sokar vault authorize} grants the app the agent would have asked the
 * person to grant, and says whose it is. One id per host, since an agent may use one app on {@code github.com} and
 * another on a GitHub Enterprise host.
 *
 * @param provider The provider the grant is for, by its name.
 * @param hosts The client id by host of the provider's device authorization URL; {@value #ANY_HOST} for any other.
 * @param owner Whose app it is, as the agent's source says, or {@code null} when it does not; shown to the person.
 */
public record AgentGrant(String provider, Map<String, String> hosts, @Nullable String owner) {

    /** The host key for every host the agent names no app of its own for. */
    public static final String ANY_HOST = "*";

    /**
     * Constructor.
     *
     * @param provider The provider.
     * @param hosts Client id by host.
     * @param owner Whose app it is, or {@code null}.
     */
    public AgentGrant {
        if (provider == null || provider.isBlank()) {
            throw new AgentException("a grant names no provider");
        }
        if (hosts == null || hosts.isEmpty()) {
            throw new AgentException("the grant for '" + provider + "' names no client id");
        }
        hosts = Map.copyOf(hosts);
    }

    /**
     * Returns the client id for a host.
     *
     * @param host The host of the provider's device authorization URL.
     * @return The id, or {@code null} when the agent names none for that host and none for any host.
     */
    public @Nullable String clientIdFor(String host) {
        final String exact = hosts.get(host);
        return exact != null ? exact : hosts.get(ANY_HOST);
    }
}
