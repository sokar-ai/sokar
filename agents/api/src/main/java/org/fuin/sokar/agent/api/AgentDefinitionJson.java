package org.fuin.sokar.agent.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Converts an {@link AgentDefinition} to and from the JSON that crosses the varlink boundary.
 * <p>
 * Written by hand and symmetric, so that both halves of the conversion sit on one screen: an
 * asymmetry between them is a field that Sokar reads as absent while the agent believes it sent
 * it, which produces behaviour nobody declared.
 */
public final class AgentDefinitionJson {

    private AgentDefinitionJson() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Renders a definition.
     *
     * @param definition What to render.
     * @return A map ready for the wire.
     */
    public static Map<String, Object> write(AgentDefinition definition) {

        final Map<String, Object> headless = new LinkedHashMap<>();
        headless.put("promptFlag", definition.headless().promptFlag());
        putIfPresent(headless, "modelFlag", definition.headless().modelFlag());
        putIfPresent(headless, "maxTurnsFlag", definition.headless().maxTurnsFlag());
        putIfPresent(headless, "verboseFlag", definition.headless().verboseFlag());
        headless.put("outputFormatFlags", definition.headless().outputFormatFlags());

        final Map<String, Object> git = new LinkedHashMap<>();
        git.put("name", definition.gitIdentity().name());
        git.put("email", definition.gitIdentity().email());

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", definition.name());
        out.put("label", definition.label());
        out.put("binary", definition.binary());
        out.put("gitIdentity", git);
        out.put("headless", headless);
        out.put("supportsResume", Boolean.valueOf(definition.supportsResume()));
        putIfPresent(out, "resumeFlag", definition.resumeFlag());
        out.put("tokenEnvironment", definition.tokenEnvironment());
        putIfPresent(out, "baseUrlEnvironment", definition.baseUrlEnvironment());
        if (definition.route() != null) {
            final Map<String, Object> route = new java.util.LinkedHashMap<>();
            route.put("upstream", definition.route().upstream());
            putIfPresent(route, "socketEnvironment", definition.route().socketEnvironment());
            route.put("endpoint", definition.route().endpoint().name().toLowerCase(
                    java.util.Locale.ROOT));
            route.put("authHeader", definition.route().authHeader());
            route.put("authPrefix", definition.route().authPrefix());
            route.put("unbrokerable", definition.route().unbrokerable());
            out.put("route", route);
        }
        out.put("allowedDomains", definition.allowedDomains());
        out.put("refusedDomains", definition.refusedDomains());
        putIfPresent(out, "configDirectory", definition.configDirectory());
        putIfPresent(out, "version", definition.version());
        out.put("artifacts", definition.artifacts().stream()
                .map(AgentDefinitionJson::writeArtifact).toList());
        out.put("installAsRoot", definition.installAsRoot());
        out.put("installAsAgent", definition.installAsAgent());
        return out;
    }

    /**
     * Reads a definition.
     *
     * @param source A map from the wire.
     * @return The definition.
     * @throws AgentException If a required field is missing or the wrong type.
     */
    public static AgentDefinition read(Map<?, ?> source) {

        final Map<?, ?> git = object(source, "gitIdentity");
        final Map<?, ?> headless = object(source, "headless");

        return new AgentDefinition(
                string(source, "name"),
                string(source, "label"),
                string(source, "binary"),
                new GitIdentity(string(git, "name"), string(git, "email")),
                new HeadlessFlags(
                        string(headless, "promptFlag"),
                        optional(headless, "modelFlag"),
                        optional(headless, "maxTurnsFlag"),
                        optional(headless, "verboseFlag"),
                        strings(headless.get("outputFormatFlags"))),
                Boolean.TRUE.equals(source.get("supportsResume")),
                optional(source, "resumeFlag"),
                map(source.get("tokenEnvironment")),
                optional(source, "baseUrlEnvironment"),
                route(source.get("route")),
                strings(source.get("allowedDomains")),
                strings(source.get("refusedDomains")),
                optional(source, "version"),
                readArtifacts(source.get("artifacts")),
                strings(source.get("installAsRoot")),
                strings(source.get("installAsAgent")),
                source.get("configDirectory") == null ? null
                        : String.valueOf(source.get("configDirectory")));
    }

    private static Map<String, Object> writeArtifact(InstallArtifact artifact) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", artifact.url());
        putIfPresent(out, "sha256", artifact.sha256());
        out.put("target", artifact.target());
        out.put("mode", artifact.mode());
        out.put("unverified", Boolean.valueOf(artifact.unverified()));
        putIfPresent(out, "reason", artifact.reason());
        return out;
    }

    private static List<InstallArtifact> readArtifacts(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        final List<InstallArtifact> result = new ArrayList<>();
        for (final Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new InstallArtifact(
                        string(map, "url"),
                        optional(map, "sha256"),
                        string(map, "target"),
                        string(map, "mode"),
                        Boolean.TRUE.equals(map.get("unverified")),
                        optional(map, "reason")));
            }
        }
        return List.copyOf(result);
    }

    private static void putIfPresent(Map<String, Object> target, String key, @Nullable Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    private static Map<?, ?> object(Map<?, ?> source, String key) {
        if (!(source.get(key) instanceof Map<?, ?> map)) {
            throw new AgentException("The agent sent no '" + key + "' object");
        }
        return map;
    }

    private static String string(Map<?, ?> source, String key) {
        if (!(source.get(key) instanceof String value)) {
            throw new AgentException("The agent sent no '" + key + "' string");
        }
        return value;
    }

    @Nullable
    private static String optional(Map<?, ?> source, String key) {
        return source.get(key) instanceof String value ? value : null;
    }

    private static List<String> strings(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        final List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return List.copyOf(result);
    }

    /**
     * Reads a provider route, absent for an agent that cannot be redirected.
     *
     * @param value Route object, or {@code null}.
     * @return Route, or {@code null}.
     */
    @Nullable
    private static ProviderRoute route(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return null;
        }
        final Object upstream = source.get("upstream");
        if (upstream == null) {
            return null;
        }
        return new ProviderRoute(String.valueOf(upstream),
                source.get("socketEnvironment") == null ? null
                        : String.valueOf(source.get("socketEnvironment")),
                map(source.get("authHeader")), map(source.get("authPrefix")),
                map(source.get("unbrokerable")),
                ProviderRoute.Endpoint.of(source.get("endpoint") == null ? null
                        : String.valueOf(source.get("endpoint")), "the agent's own description"));
    }

    private static Map<String, String> map(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        final Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }
}
