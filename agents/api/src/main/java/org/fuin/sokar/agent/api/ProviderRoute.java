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
 * @param endpoint What the agent is able to address the proxy as.
 */
public record ProviderRoute(String upstream, @Nullable String socketEnvironment,
        Map<String, String> authHeader, Map<String, String> authPrefix,
        Map<String, String> unbrokerable, Endpoint endpoint) {

    /**
     * What an agent can be pointed at.
     * <p>
     * Declared by the agent because it is a property of the agent, not of the provider: the same
     * provider is reached over a socket by one agent and only as a URL by another. Sokar decides
     * how to satisfy it, and the two are satisfied very differently - a socket is a file the
     * container mounts, while a URL needs something listening in the container's own network
     * namespace, because a host-side listener is either unreachable from a rootless container or
     * bound to every interface.
     */
    public enum Endpoint {

        /** A unix socket path in an environment variable. The default, and the safer one. */
        SOCKET,

        /** An {@code http://} address, for an agent that can only be given a URL. */
        URL;

        /**
         * Returns the endpoint a definition names.
         *
         * @param declared Value from the definition, or {@code null} for the default.
         * @param origin Where the definition came from, for the error message.
         * @return The endpoint.
         */
        public static Endpoint of(@Nullable String declared, String origin) {
            if (declared == null || declared.isBlank()) {
                return SOCKET;
            }
            return switch (declared.toLowerCase(java.util.Locale.ROOT)) {
                case "socket" -> SOCKET;
                case "url" -> URL;
                default -> throw new AgentException(origin + ": 'proxy.endpoint' must be 'socket'"
                        + " or 'url', not '" + declared + "'");
            };
        }
    }

    /**
     * Constructor for a route that can carry every credential kind.
     *
     * @param upstream Real API endpoint.
     * @param socketEnvironment Variable naming the proxy socket, or {@code null}.
     * @param authHeader Header to use, by credential kind.
     * @param authPrefix Value prefix, by credential kind.
     */
    public ProviderRoute(String upstream, @Nullable String socketEnvironment,
            Map<String, String> authHeader, Map<String, String> authPrefix) {
        this(upstream, socketEnvironment, authHeader, authPrefix, Map.of(), Endpoint.SOCKET);
    }

    /**
     * Constructor for a route with no endpoint of its own declared.
     *
     * @param upstream Real API endpoint.
     * @param socketEnvironment Variable naming the proxy socket, or {@code null}.
     * @param authHeader Header to use, by credential kind.
     * @param authPrefix Value prefix, by credential kind.
     * @param unbrokerable Why a credential kind cannot be brokered.
     */
    public ProviderRoute(String upstream, @Nullable String socketEnvironment,
            Map<String, String> authHeader, Map<String, String> authPrefix,
            Map<String, String> unbrokerable) {
        this(upstream, socketEnvironment, authHeader, authPrefix, unbrokerable, Endpoint.SOCKET);
    }

    /**
     * Returns why a credential kind cannot go through the proxy.
     *
     * @param type Credential kind.
     * @return The reason, or {@code null} when the kind is fine.
     */
    @Nullable
    public String unbrokerableReason(@Nullable String type) {
        return type == null ? null : unbrokerable.get(type);
    }

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
        unbrokerable = Map.copyOf(unbrokerable);
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
