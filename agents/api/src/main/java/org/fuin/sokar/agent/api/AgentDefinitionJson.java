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
 * it, which produces behavior nobody declared.
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
        if (!definition.packaged().isEmpty()) {
            final java.util.List<Object> trees = new java.util.ArrayList<>();
            for (final PackagedTree tree : definition.packaged()) {
                trees.add(Map.of("source", tree.source(), "target", tree.target()));
            }
            out.put("packaged", trees);
        }
        if (definition.provider() != null) {
            final Map<String, Object> provider = new java.util.LinkedHashMap<>();
            provider.put("dialect", definition.provider().dialect());
            putIfPresent(provider, "default", definition.provider().defaultProvider());
            provider.put("endpoint", definition.provider().endpoint().name().toLowerCase(
                    java.util.Locale.ROOT));
            putIfPresent(provider, "socketEnvironment",
                    definition.provider().socketEnvironment());
            putIfPresent(provider, "baseUrlEnvironment",
                    definition.provider().baseUrlEnvironment());
            out.put("provider", provider);
        }
        out.put("allowedDomains", definition.allowedDomains());
        out.put("refusedDomains", definition.refusedDomains());
        putIfPresent(out, "configDirectory", definition.configDirectory());
        putIfPresent(out, "loginArguments", definition.loginArguments());
        putIfPresent(out, "loginDocumentation", definition.loginDocumentation());
        out.put("sandboxedArguments", definition.sandboxedArguments());
        out.put("instructionArguments", definition.instructionArguments());
        if (definition.atRest() != null) {
            out.put("atRest", Map.of("shows", definition.atRest().shows(), "lacks", definition.atRest().lacks()));
        }
        if (!definition.grants().isEmpty()) {
            out.put("grants", definition.grants().stream().map(grant -> {
                final Map<String, Object> one = new LinkedHashMap<>();
                one.put("provider", grant.provider());
                one.put("hosts", grant.hosts());
                putIfPresent(one, "owner", grant.owner());
                return one;
            }).toList());
        }
        if (definition.ready() != null) {
            out.put("readyMarker", definition.ready().text());
            putIfPresent(out, "readyWithinSeconds", definition.ready().withinSeconds());
        }
        if (definition.waiting() != null) {
            out.put("waiting", writeWaiting(definition.waiting()));
        }
        if (definition.sessionIds() != null) {
            final Map<String, Object> ids = new LinkedHashMap<>();
            ids.put("record", definition.sessionIds().record());
            putIfPresent(ids, "key", definition.sessionIds().key());
            putIfPresent(ids, "directory", definition.sessionIds().directory());
            putIfPresent(ids, "suffix", definition.sessionIds().suffix());
            out.put("sessionIds", ids);
        }
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
                        headless.get("promptFlag") == null ? ""
                                : String.valueOf(headless.get("promptFlag")),
                        optional(headless, "modelFlag"),
                        optional(headless, "maxTurnsFlag"),
                        optional(headless, "verboseFlag"),
                        strings(headless.get("outputFormatFlags"))),
                Boolean.TRUE.equals(source.get("supportsResume")),
                optional(source, "resumeFlag"),
                map(source.get("tokenEnvironment")),
                agentProvider(source.get("provider")),
                strings(source.get("allowedDomains")),
                strings(source.get("refusedDomains")),
                optional(source, "version"),
                readArtifacts(source.get("artifacts")),
                strings(source.get("installAsRoot")),
                strings(source.get("installAsAgent")),
                packaged(source.get("packaged")),
                source.get("configDirectory") == null ? null
                        : String.valueOf(source.get("configDirectory")),
                source.get("loginArguments") == null ? null
                        : strings(source.get("loginArguments")),
                source.get("loginDocumentation") == null ? null
                        : String.valueOf(source.get("loginDocumentation")),
                strings(source.get("sandboxedArguments")),
                source.get("readyMarker") == null ? null
                        : new ReadyMarker(String.valueOf(source.get("readyMarker")),
                                source.get("readyWithinSeconds") instanceof Number within ? within.intValue() : null),
                source.get("waiting") instanceof Map<?, ?> waiting ? readWaiting(waiting) : null,
                source.get("sessionIds") instanceof Map<?, ?> ids ? new SessionIds(map(ids.get("record")),
                        optional(ids, "key"), optional(ids, "directory"), optional(ids, "suffix")) : null,
                strings(source.get("instructionArguments")),
                source.get("atRest") instanceof Map<?, ?> rest
                        ? new AtRest(strings(rest.get("shows")), strings(rest.get("lacks"))) : null,
                source.get("grants") instanceof List<?> grants ? grants.stream()
                        .filter(Map.class::isInstance).map(Map.class::cast)
                        .map(grant -> new AgentGrant(String.valueOf(grant.get("provider")), map(grant.get("hosts")),
                                optional(grant, "owner")))
                        .toList() : List.of());
    }

    private static Map<String, Object> writeWaiting(Waiting waiting) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("screen", waiting.screen().stream().map(AgentDefinitionJson::writeRule).toList());
        out.put("notItsScreen", waiting.notItsScreen().stream().map(AgentDefinitionJson::writeRule).toList());
        if (waiting.lastMessage() != null) {
            final Map<String, Object> last = new LinkedHashMap<>();
            last.put("record", waiting.lastMessage().record());
            last.put("text", waiting.lastMessage().text());
            out.put("lastMessage", last);
        }
        return out;
    }

    private static Map<String, Object> writeRule(Waiting.Rule rule) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("contains", rule.contains());
        putIfPresent(out, "inLastLines", rule.lastLines());
        putIfPresent(out, "for", rule.waitingFor());
        return out;
    }

    private static Waiting readWaiting(Map<?, ?> source) {
        final Waiting.LastMessage last = source.get("lastMessage") instanceof Map<?, ?> found
                ? new Waiting.LastMessage(map(found.get("record")), string(found, "text")) : null;
        return new Waiting(readRules(source.get("screen")), readRules(source.get("notItsScreen")), last);
    }

    private static List<Waiting.Rule> readRules(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        final List<Waiting.Rule> rules = new ArrayList<>();
        for (final Object each : list) {
            if (each instanceof Map<?, ?> rule) {
                rules.add(new Waiting.Rule(string(rule, "contains"),
                        rule.get("inLastLines") instanceof Number lines ? lines.intValue() : null, optional(rule, "for")));
            }
        }
        return rules;
    }

    private static Map<String, Object> writeArtifact(InstallArtifact artifact) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", artifact.url());
        putIfPresent(out, "sha256", artifact.sha256());
        out.put("target", artifact.target());
        out.put("mode", artifact.mode());
        out.put("unverified", Boolean.valueOf(artifact.unverified()));
        putIfPresent(out, "reason", artifact.reason());
        putIfPresent(out, "license", artifact.license());
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
                        optional(map, "reason"),
                        optional(map, "license")));
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
     * Reads what an agent says about providers, absent when it cannot be redirected.
     *
     * @param value Provider object, or {@code null}.
     * @return What the agent declared, or {@code null}.
     */
    @Nullable
    private static AgentProvider agentProvider(@Nullable Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return null;
        }
        final Object dialect = source.get("dialect");
        if (dialect == null) {
            return null;
        }
        return new AgentProvider(String.valueOf(dialect),
                text(source.get("default")),
                ProviderRoute.Endpoint.of(source.get("endpoint") == null ? null
                        : String.valueOf(source.get("endpoint")), "the agent's own description"),
                text(source.get("socketEnvironment")),
                text(source.get("baseUrlEnvironment")));
    }

    @Nullable
    private static String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static java.util.List<PackagedTree> packaged(@Nullable Object value) {
        final java.util.List<PackagedTree> trees = new java.util.ArrayList<>();
        if (value instanceof java.util.List<?> list) {
            for (final Object element : list) {
                if (element instanceof Map<?, ?> entry) {
                    trees.add(new PackagedTree(String.valueOf(entry.get("source")),
                            String.valueOf(entry.get("target"))));
                }
            }
        }
        return java.util.List.copyOf(trees);
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
