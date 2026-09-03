package org.fuin.sokar.agent.api;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How Sokar's vault proxy stands in for the provider an agent talks to.
 * <p>
 * The agent is handed a phantom token, not the real credential. Something has to accept that
 * token and reissue the request upstream with the real one, and this record is what tells the
 * proxy how: where to forward, and which header the credential belongs in.
 * <p>
 * <strong>The header depends on the credential type.</strong> An OAuth token goes in
 * {@code Authorization: Bearer …} and an API key in a provider-specific header - for Anthropic,
 * {@code x-api-key} with no prefix. Sending one as the other fails as an authentication error,
 * which is indistinguishable from a bad key, so both are declared per type with
 * {@link AgentDefinition#DEFAULT_TOKEN_KEY} as the fallback.
 *
 * @param upstream Real API endpoint the proxy forwards to.
 * @param socketEnvironment Variable naming the unix socket the agent should use, or {@code null}
 *        if the agent cannot be pointed at one.
 * @param authHeader Credential type to header name.
 * @param authPrefix Credential type to the string placed before the credential.
 */
public record ProviderRoute(String upstream, @Nullable String socketEnvironment,
        Map<String, String> authHeader, Map<String, String> authPrefix) {

    /**
     * Constructor with all data.
     *
     * @param upstream Real API endpoint.
     * @param socketEnvironment Variable naming the socket, or {@code null}.
     * @param authHeader Credential type to header name.
     * @param authPrefix Credential type to credential prefix.
     */
    public ProviderRoute {
        if (upstream == null || upstream.isBlank()) {
            throw new AgentException("A provider route needs an upstream");
        }
        try {
            final URI uri = URI.create(upstream);
            if (uri.getHost() == null || !"https".equals(uri.getScheme())) {
                // Plain HTTP upstream would send the real credential in the clear, and a URI
                // without a host cannot produce the deny rule that stops the agent going direct.
                throw new AgentException("Upstream '" + upstream
                        + "' must be an https URL naming a host");
            }
        } catch (IllegalArgumentException ex) {
            throw new AgentException("Upstream '" + upstream + "' is not a URL");
        }
        authHeader = Map.copyOf(authHeader);
        authPrefix = Map.copyOf(authPrefix);
    }

    /**
     * Returns the host the agent must not be allowed to reach directly.
     * <p>
     * Whenever a phantom token is issued, this host is withheld from the firewall. An agent that
     * ignores the redirect - Claude Code compiles in its own base URL - then gets a dropped
     * connection and an audit entry instead of quietly sending the phantom token to the provider.
     *
     * @return Host part of the upstream.
     */
    public String upstreamHost() {
        return URI.create(upstream).getHost();
    }

    /**
     * Returns the header the credential belongs in for the given type.
     *
     * @param credentialType Credential type, for example {@code oauth}.
     * @return Header name, {@code Authorization} when the definition names none.
     */
    public String authHeaderFor(String credentialType) {
        return authHeader.getOrDefault(credentialType,
                authHeader.getOrDefault(AgentDefinition.DEFAULT_TOKEN_KEY, "Authorization"));
    }

    /**
     * Returns the string placed before the credential for the given type.
     *
     * @param credentialType Credential type, for example {@code oauth}.
     * @return Prefix, empty when the definition names none.
     */
    public String authPrefixFor(String credentialType) {
        return authPrefix.getOrDefault(credentialType,
                authPrefix.getOrDefault(AgentDefinition.DEFAULT_TOKEN_KEY, ""));
    }
}
