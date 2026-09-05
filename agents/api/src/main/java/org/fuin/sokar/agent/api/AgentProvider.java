package org.fuin.sokar.agent.api;

import org.jspecify.annotations.Nullable;

/**
 * What an agent says about the providers it can drive.
 * <p>
 * Everything here is a property of the agent, not of whoever serves the models: which wire format
 * it speaks, which provider it uses unless told otherwise, and what shape of endpoint it is able
 * to address. The provider's own half - upstream, header, prefix, path - is a
 * {@link ProviderDefinition} and is declared once, elsewhere.
 * <p>
 * <strong>The endpoint shape belongs here for a reason that cost a day to learn.</strong> The
 * same provider is reached over a unix socket by one agent and only as a URL by another, and the
 * two are satisfied very differently: a socket is a file the container mounts, while a URL needs
 * something listening inside the container's own network namespace.
 *
 * @param dialect Wire format the agent speaks, or {@code native} when it speaks whatever the
 *        provider's own is.
 * @param defaultProvider Provider used when the operator names none, or {@code null} when the
 *        agent has no default and one must be chosen.
 * @param endpoint What the agent is able to address the proxy as.
 * @param socketEnvironment Variable naming the unix socket, or {@code null}.
 * @param baseUrlEnvironment Variable naming the endpoint URL, or {@code null}.
 */
public record AgentProvider(String dialect, @Nullable String defaultProvider,
        ProviderRoute.Endpoint endpoint, @Nullable String socketEnvironment,
        @Nullable String baseUrlEnvironment) {

    /**
     * Constructor with all data.
     *
     * @param dialect Wire format spoken.
     * @param defaultProvider Provider used when none is named.
     * @param endpoint Endpoint shape the agent can address.
     * @param socketEnvironment Variable naming the socket.
     * @param baseUrlEnvironment Variable naming the URL.
     */
    public AgentProvider {
        if (dialect == null || dialect.isBlank()) {
            throw new AgentException("An agent must say which dialect it speaks, or 'native'");
        }
        if (endpoint == ProviderRoute.Endpoint.SOCKET && socketEnvironment == null
                && baseUrlEnvironment == null) {
            // An agent that can be pointed at a socket but names no variable for it cannot
            // actually be pointed anywhere, and would fail by talking straight to the provider.
            throw new AgentException("An agent addressing a socket must name the variable"
                    + " carrying it");
        }
    }

    /**
     * Returns whether this agent can drive the given provider.
     *
     * @param provider The provider.
     * @return {@code true} when the provider serves a dialect this agent speaks.
     */
    public boolean canDrive(ProviderDefinition provider) {
        return provider.serves(dialect);
    }
}
