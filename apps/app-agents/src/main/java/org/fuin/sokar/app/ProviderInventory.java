package org.fuin.sokar.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.agent.api.ProviderDefinition;
import org.fuin.sokar.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/**
 * The model providers this machine declares, and what the vault holds for each.
 * <p>
 * The CLI prints it and the daemon serializes it, so "which name does a credential go under, and
 * is one there" is answered in one place. It carries no secret: names, kinds and whether something
 * is stored, never a value.
 */
public final class ProviderInventory {

    /**
     * What was found.
     *
     * @param providers One row per declared provider.
     * @param readable Whether the vault could be read. When it could not, whether anything is
     *        stored is unknown rather than false.
     */
    public record Listing(List<Map<String, Object>> providers, boolean readable) {

        /** @return The listing as plain values, for a caller that has to put it on a wire. */
        public Map<String, Object> asMap() {
            return Map.of("providers", providers, "readable", readable);
        }
    }

    private ProviderInventory() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Lists every declared provider with what the vault holds for it.
     * <p>
     * Cheap in the ordinary case: the agents are started only when the vault holds a name that is
     * not a provider's, the one situation in which a credential can still be under an agent's name.
     *
     * @param context Where the providers, the vault and the agents come from.
     * @return The listing.
     */
    public static Listing list(SokarContext context) {
        final Map<String, ProviderDefinition> declared = context.providers();
        final java.util.Optional<Map<String, VaultEntry>> readable = context.readableCredentials();
        final Map<String, VaultEntry> stored = readable.orElseGet(Map::of);

        final java.util.Set<String> unexplained = stored.keySet().stream()
                .filter(name -> !declared.containsKey(name))
                .collect(java.util.stream.Collectors.toSet());
        final Map<String, String> legacyAgentByProvider = new LinkedHashMap<>();
        if (!unexplained.isEmpty()) {
            try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
                for (final var agent : agents.all()) {
                    final var provider = agent.definition().provider();
                    if (provider != null && unexplained.contains(agent.name())) {
                        legacyAgentByProvider.putIfAbsent(provider.defaultProvider(),
                                agent.name());
                    }
                }
            } catch (RuntimeException ex) {
                // A machine whose agents cannot be asked still has providers worth listing. The
                // key falls back to the provider's own name, which is where a credential belongs.
            }
        }

        return new Listing(declared.values().stream()
                .map(provider -> row(provider, stored, legacyAgentByProvider.get(provider.name())))
                .toList(), readable.isPresent());
    }

    /**
     * Returns one provider as an interface reads it.
     * <p>
     * Separated so the key resolution can be exercised: the interesting case is a vault written
     * before credentials were keyed by provider, which no fixture of an empty vault reaches.
     *
     * @param provider The provider.
     * @param stored What the vault holds, by name.
     * @param legacyAgent An agent whose own name this provider's credential may still be under,
     *        or {@code null} when there is none.
     * @return The row, carrying no credential value.
     */
    public static Map<String, Object> row(ProviderDefinition provider, Map<String, VaultEntry> stored,
            @Nullable String legacyAgent) {
        final String key = SelectedProvider.credentialKey(stored.keySet(),
                legacyAgent == null ? provider.name() : legacyAgent, provider.name());
        final VaultEntry entry = stored.get(key);
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", provider.name());
        row.put("label", provider.label());
        row.put("upstream", provider.upstream());
        row.put("dialects", List.copyOf(provider.dialects().keySet()));
        row.put("authenticated", entry != null);
        row.put("credentialType", entry == null || entry.type() == null ? "" : entry.type());
        row.put("credentialName", key);
        // Always the provider's name, even when the vault still answers under an agent's: that is
        // where a credential belongs, and offering the agent's name taught people to store it there.
        row.put("storeCommand", "sokar vault put " + provider.name());
        return row;
    }
}
