package org.fuin.sokar.agent.api;

import java.net.URI;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How Sokar's vault proxy stands in for the provider an agent talks to.
 * <p>
 * <strong>This is a resolved pair, not a declaration.</strong> It is built by
 * {@link #of(ProviderDefinition, AgentProvider)} from the provider's half - upstream, header,
 * prefix, path - and the agent's half - which dialect it speaks and what shape of endpoint it can
 * address. Neither side states the other's facts, which is what stops the same provider being
 * described once per agent that reaches it.
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
 * @param endpointPath Path appended to the endpoint the agent is given, because the dialect it
 *        speaks is served under it. Empty when the provider serves it at the root.
 */
public record ProviderRoute(String upstream, @Nullable String socketEnvironment,
        Map<String, String> authHeader, Map<String, String> authPrefix,
        Map<String, String> unbrokerable, Endpoint endpoint, String endpointPath,
        Map<String, String> authQuery) {

    /**
     * Constructor for a route whose keys all travel in a header, the shape every caller built before a key
     * could go in the URL.
     *
     * @param upstream Base URL.
     * @param socketEnvironment Variable naming the socket, or {@code null}.
     * @param authHeader Credential type to header name.
     * @param authPrefix Credential type to prefix.
     * @param unbrokerable Credential type to why it cannot be brokered.
     * @param endpoint How the agent reaches the broker.
     * @param endpointPath Path the dialect is served under.
     */
    public ProviderRoute(String upstream, @Nullable String socketEnvironment, Map<String, String> authHeader,
            Map<String, String> authPrefix, Map<String, String> unbrokerable, Endpoint endpoint,
            String endpointPath) {
        this(upstream, socketEnvironment, authHeader, authPrefix, unbrokerable, endpoint, endpointPath, Map.of());
    }

    /**
     * Returns the route an agent takes to a provider.
     * <p>
     * The only place the two halves meet. Everything the proxy needs to forward a request comes
     * from the provider; everything about how the agent is told where to send it comes from the
     * agent.
     *
     * @param provider Who serves the models.
     * @param agent What the agent says it can speak and address.
     * @return The resolved route.
     * @throws AgentException If the provider does not serve a dialect the agent speaks.
     */
    public static ProviderRoute of(ProviderDefinition provider, AgentProvider agent) {
        return new ProviderRoute(provider.upstream(), agent.socketEnvironment(),
                provider.authHeader(), provider.authPrefix(), provider.unbrokerable(),
                agent.endpoint(), provider.pathFor(agent.dialect()), provider.authQuery());
    }

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
        this(upstream, socketEnvironment, authHeader, authPrefix, Map.of(), Endpoint.SOCKET, "");
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
        this(upstream, socketEnvironment, authHeader, authPrefix, unbrokerable, Endpoint.SOCKET,
                "");
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
        endpointPath = endpointPath == null ? "" : endpointPath;
    }

    /**
     * Returns the endpoint an agent should be given, with the dialect's path already on it.
     *
     * @param base Address the agent can reach, without a trailing slash.
     * @return Endpoint to hand to the agent.
     */
    public String endpointFor(String base) {
        final String trimmed = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return trimmed + endpointPath;
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

    /**
     * Returns the query parameter the credential of a type travels in, or {@code null} for a header.
     *
     * @param credentialType Credential type, for example {@code api-key}.
     * @return The parameter's name, or {@code null}.
     */
    public @Nullable String authQueryFor(String credentialType) {
        return authQuery.getOrDefault(credentialType, authQuery.get(AgentDefinition.DEFAULT_TOKEN_KEY));
    }
}
