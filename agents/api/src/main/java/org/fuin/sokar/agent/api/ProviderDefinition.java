package org.fuin.sokar.agent.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A provider of models, declared once and reachable by any agent that speaks one of its dialects.
 * <p>
 * This is the half of the old inline {@code proxy} block that belongs to whoever serves the
 * models rather than to whoever asks them: where the requests go, which header carries a
 * credential, and what path each wire format is served under. It exists because two agents
 * reaching the same provider described it twice, in their own words, and the descriptions were
 * free to drift.
 * <p>
 * <strong>There is deliberately no behavior here.</strong> A provider is data, which is why it
 * ships as a file rather than as a binary the way an agent does.
 *
 * @param name Short name, and the key an operator types.
 * @param label Human-readable label.
 * @param upstream Real API endpoint requests are forwarded to.
 * @param dialects Wire format to the path it is served under, the provider's own dialect first.
 * @param authHeader Credential type to the header the credential belongs in.
 * @param authPrefix Credential type to the string placed before the credential.
 * @param unbrokerable Credential type to why it cannot go through the proxy.
 * @param tokenEnvironment Credential type to the variable this provider's credential is
 *        conventionally read from.
 */
public record ProviderDefinition(String name, String label, String upstream,
        Map<String, String> dialects, Map<String, String> authHeader,
        Map<String, String> authPrefix, Map<String, String> unbrokerable,
        Map<String, String> tokenEnvironment) {

    /** Dialect an agent names when it speaks whatever the provider's own format is. */
    public static final String NATIVE_DIALECT = "native";

    /**
     * Constructor with all data.
     *
     * @param name Short name.
     * @param label Human-readable label.
     * @param upstream Real API endpoint.
     * @param dialects Wire format to path.
     * @param authHeader Header by credential type.
     * @param authPrefix Prefix by credential type.
     * @param unbrokerable Reason a credential type cannot be brokered.
     * @param tokenEnvironment Variable by credential type.
     */
    public ProviderDefinition {
        if (!name.matches("[a-z0-9][a-z0-9-]{0,30}")) {
            // The name becomes a vault key and reaches command lines, both stricter than YAML.
            throw new AgentException("Invalid provider name '" + name
                    + "', expected lower-case letters, digits and hyphens");
        }
        if (upstream == null || upstream.isBlank()) {
            throw new AgentException("Provider '" + name + "' needs an upstream");
        }
        try {
            final URI uri = URI.create(upstream);
            if (uri.getHost() == null || !"https".equals(uri.getScheme())) {
                // Plain HTTP would send the real credential in the clear, and a URI without a
                // host cannot produce the deny rule that stops an agent going direct.
                throw new AgentException("Provider '" + name + "' upstream '" + upstream
                        + "' must be an https URL naming a host");
            }
        } catch (IllegalArgumentException ex) {
            throw new AgentException("Provider '" + name + "' upstream '" + upstream
                    + "' is not a URL");
        }
        if (dialects.isEmpty()) {
            // Without one there is nothing for an agent to match against, so the provider could
            // never be selected - a definition that cannot be used is a mistake, not a choice.
            throw new AgentException("Provider '" + name + "' declares no dialects");
        }
        if (dialects.containsKey(NATIVE_DIALECT)) {
            throw new AgentException("Provider '" + name + "' declares a dialect called '"
                    + NATIVE_DIALECT + "', which is the name an agent uses to ask for whichever"
                    + " dialect comes first");
        }
        dialects = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(dialects));
        authHeader = Map.copyOf(authHeader);
        authPrefix = Map.copyOf(authPrefix);
        unbrokerable = Map.copyOf(unbrokerable);
        tokenEnvironment = Map.copyOf(tokenEnvironment);
    }

    /**
     * Returns the host an agent must not be allowed to reach directly.
     * <p>
     * Whenever a phantom token is issued, this host is withheld from the firewall, so an agent
     * that ignores the redirect gets a dropped connection and an audit entry rather than quietly
     * sending the phantom token to the provider.
     *
     * @return Host part of the upstream.
     */
    public String upstreamHost() {
        return URI.create(upstream).getHost();
    }

    /**
     * Returns the dialect this provider is, which is the first one it declares.
     *
     * @return Wire format name.
     */
    public String nativeDialect() {
        return dialects.keySet().iterator().next();
    }

    /**
     * Returns whether an agent speaking the given dialect can drive this provider.
     *
     * @param dialect Wire format the agent speaks, or {@link #NATIVE_DIALECT}.
     * @return {@code true} when this provider serves it.
     */
    public boolean serves(String dialect) {
        return NATIVE_DIALECT.equals(dialect) || dialects.containsKey(dialect);
    }

    /**
     * Returns the path the given dialect is served under.
     * <p>
     * The same provider serves different wire formats at different paths, and getting this wrong
     * does not fail cleanly: a base URL missing its path answers "model not found" rather than
     * 404, which reads as a bad model name.
     *
     * @param dialect Wire format, or {@link #NATIVE_DIALECT} for whichever comes first.
     * @return Path to append to the endpoint, possibly empty.
     * @throws AgentException If this provider does not serve that dialect.
     */
    public String pathFor(String dialect) {
        final String wanted = NATIVE_DIALECT.equals(dialect) ? nativeDialect() : dialect;
        final String path = dialects.get(wanted);
        if (path == null) {
            throw new AgentException("Provider '" + name + "' does not serve '" + wanted
                    + "', only " + dialects.keySet());
        }
        return path;
    }

    /**
     * Returns the header the credential belongs in for the given type.
     *
     * @param credentialType Credential type, for example {@code oauth}.
     * @return Header name, {@code Authorization} when none is declared.
     */
    public String authHeaderFor(String credentialType) {
        return authHeader.getOrDefault(credentialType,
                authHeader.getOrDefault(AgentDefinition.DEFAULT_TOKEN_KEY, "Authorization"));
    }

    /**
     * Returns the string placed before the credential for the given type.
     *
     * @param credentialType Credential type, for example {@code oauth}.
     * @return Prefix, empty when none is declared.
     */
    public String authPrefixFor(String credentialType) {
        return authPrefix.getOrDefault(credentialType,
                authPrefix.getOrDefault(AgentDefinition.DEFAULT_TOKEN_KEY, ""));
    }

    /**
     * Returns the variable this provider's credential is conventionally read from.
     *
     * @param credentialType Credential type.
     * @return Variable name, or {@code null} when none is declared.
     */
    @Nullable
    public String tokenVariable(String credentialType) {
        final String specific = tokenEnvironment.get(credentialType);
        return specific != null ? specific
                : tokenEnvironment.get(AgentDefinition.DEFAULT_TOKEN_KEY);
    }

    /**
     * Returns why a credential kind cannot go through the proxy.
     *
     * @param type Credential kind, or {@code null}.
     * @return The reason, or {@code null} when the kind is fine.
     */
    @Nullable
    public String unbrokerableReason(@Nullable String type) {
        return type == null ? null : unbrokerable.get(type);
    }

    /**
     * Returns the domains a task needs on top of the agent's own.
     *
     * @return The upstream host.
     */
    public List<String> domains() {
        return List.of(upstreamHost());
    }
}
