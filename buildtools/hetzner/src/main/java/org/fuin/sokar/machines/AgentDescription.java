package org.fuin.sokar.machines;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;
import org.fuin.sokar.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * What tier 1 needs to know about the agent it drives, read from that agent's own {@code describe}.
 * <p>
 * Read here, in Java, and handed to {@code e2e-tier1.sh} as its environment: the script read the
 * same eight fields with eight {@code python3} one-liners, which put a second runtime on every build
 * machine for the sake of reading JSON. Read rather than assumed, so nothing names one agent's tool,
 * prompt flag or provider.
 *
 * @param version the agent CLI's version
 * @param allowedDomains what it may reach
 * @param binary the tool it runs
 * @param promptFlag how it takes a prompt, or empty
 * @param provider its default provider, or empty
 * @param socketEnvironment the variable naming the broker's socket, or empty
 * @param baseUrlEnvironment the variable naming the broker's address, or empty
 * @param endpoint {@code socket} or {@code url}: how it is pointed at the broker
 */
record AgentDescription(String version, List<String> allowedDomains, String binary, String promptFlag,
        String provider, String socketEnvironment, String baseUrlEnvironment, String endpoint) {

    /**
     * Reads a {@code describe} response.
     *
     * @param json what the agent printed
     * @return the fields tier 1 uses
     * @throws IllegalArgumentException if it is not a description, or lacks a field every agent has
     */
    static AgentDescription parse(String json) {
        final @Nullable Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (JsonException ex) {
            throw new IllegalArgumentException("not an agent description: " + brief(json), ex);
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("definition") instanceof Map<?, ?> definition)) {
            throw new IllegalArgumentException("not an agent description: " + brief(json));
        }
        final Map<?, ?> headless = definition.get("headless") instanceof Map<?, ?> map ? map : Map.of();
        final Map<?, ?> provider = definition.get("provider") instanceof Map<?, ?> map ? map : Map.of();
        return new AgentDescription(required(definition, "version"), domains(definition.get("allowedDomains")),
                required(definition, "binary"), text(headless.get("promptFlag")), text(provider.get("default")),
                text(provider.get("socketEnvironment")), text(provider.get("baseUrlEnvironment")),
                // An agent that says nothing about its endpoint is addressed by socket.
                text(provider.get("endpoint")).isEmpty() ? "socket" : text(provider.get("endpoint")));
    }

    /**
     * Says it as the variables {@code e2e-tier1.sh} reads.
     *
     * @return name to value, in a stable order
     */
    Map<String, String> environment() {
        final Map<String, String> environment = new LinkedHashMap<>();
        environment.put("SOKAR_E2E_CLI_VERSION", version);
        environment.put("SOKAR_E2E_DOMAINS", String.join("\n", allowedDomains));
        environment.put("SOKAR_E2E_AGENT_BINARY", binary);
        environment.put("SOKAR_E2E_PROMPT_FLAG", promptFlag);
        environment.put("SOKAR_E2E_PROVIDER", provider);
        environment.put("SOKAR_E2E_SOCKET_ENV", socketEnvironment);
        environment.put("SOKAR_E2E_BASE_URL_ENV", baseUrlEnvironment);
        environment.put("SOKAR_E2E_ENDPOINT", endpoint);
        return environment;
    }

    private static String required(Map<?, ?> definition, String key) {
        final String value = text(definition.get(key));
        if (value.isEmpty()) {
            throw new IllegalArgumentException("the agent description has no " + key);
        }
        return value;
    }

    private static List<String> domains(@Nullable Object value) {
        return value instanceof List<?> list ? list.stream().map(AgentDescription::text).filter(s -> !s.isEmpty()).toList()
                : List.of();
    }

    private static String text(@Nullable Object value) {
        return value instanceof String text ? text : "";
    }

    private static String brief(String text) {
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

}
