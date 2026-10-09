package org.fuin.sokar.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.AgentGrant;
import org.fuin.sokar.agent.api.ProviderDefinition;
import org.fuin.sokar.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/**
 * The entry {@code sokar vault authorize} makes for a provider nothing is stored for yet.
 * <p>
 * What everybody shares - the kind of flow, its URLs, its scopes - is the provider's {@code grant}; the client id is the
 * signing-in agent's own, by the host of the provider's device authorization URL, so Sokar grants the app the agent
 * itself would have asked the person to grant, and ships none of its own. A public client: no secret.
 */
final class GrantEntry {

    /** The value of a public client's entry: no secret, which {@code DeviceGrant.Client} reads as none. */
    static final String NO_SECRET = "-";

    /**
     * What was chosen.
     *
     * @param entry The entry to store, or {@code null} when refused.
     * @param agent The agent whose app it is, or {@code null} when refused.
     * @param refusal Why nothing can be made, or {@code null}.
     */
    record Choice(@Nullable VaultEntry entry, @Nullable String agent, @Nullable String refusal) {

        static Choice refused(final String why) {
            return new Choice(null, null, why);
        }
    }

    private GrantEntry() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Chooses the entry for a provider from the agents installed.
     *
     * @param provider The provider the person names.
     * @param agents The agents installed here.
     * @param agentName The agent the person named with {@code --agent}, or {@code null}.
     * @return The entry and its agent, or why there is none.
     */
    static Choice choose(final ProviderDefinition provider, final List<AgentDefinition> agents,
            final @Nullable String agentName) {
        final Map<String, String> grant = provider.grant();
        if (grant.isEmpty()) {
            return Choice.refused("'" + provider.name() + "' is reached with a key, which nothing grants; store it with"
                    + " 'sokar vault put " + provider.name() + "'");
        }
        final List<AgentDefinition> signing = agents.stream()
                .filter(agent -> agent.grantFor(provider.name()) != null).toList();
        if (signing.isEmpty()) {
            return Choice.refused("no installed agent signs in to '" + provider.name() + "', so there is no client id"
                    + " to grant; install one that does, or store the entry with 'sokar vault put " + provider.name()
                    + " --type " + grant.getOrDefault("kind", "oauth-device") + "'");
        }
        final AgentDefinition chosen;
        if (agentName != null) {
            chosen = signing.stream().filter(agent -> agent.name().equals(agentName)).findFirst().orElse(null);
            if (chosen == null) {
                return Choice.refused("'" + agentName + "' does not sign in to '" + provider.name() + "'; these do: "
                        + names(signing));
            }
        } else if (signing.size() > 1) {
            return Choice.refused("several agents sign in to '" + provider.name() + "', each with its own app: "
                    + names(signing) + "; name one with --agent");
        } else {
            chosen = signing.getFirst();
        }
        final AgentGrant agentGrant = java.util.Objects.requireNonNull(chosen.grantFor(provider.name()));
        final String host = host(grant.get("device_authorization_url"));
        final String clientId = agentGrant.clientIdFor(host);
        if (clientId == null) {
            return Choice.refused("'" + chosen.name() + "' names no app for " + host + "; store the entry with 'sokar"
                    + " vault put " + provider.name() + "'");
        }
        final Map<String, String> settings = new LinkedHashMap<>();
        settings.put("client_id", clientId);
        grant.forEach((key, value) -> {
            if (!"kind".equals(key)) {
                settings.put(key, value);
            }
        });
        if (agentGrant.owner() != null) {
            settings.put("client_owner", agentGrant.owner());
        }
        return new Choice(new VaultEntry(NO_SECRET, grant.getOrDefault("kind", "oauth-device"), settings),
                chosen.name(), null);
    }

    private static String host(final @Nullable String url) {
        try {
            final String host = url == null ? null : java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException ex) {
            return "";
        }
    }

    private static String names(final List<AgentDefinition> agents) {
        return String.join(", ", agents.stream().map(AgentDefinition::name).toList());
    }
}
