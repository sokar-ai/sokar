package org.fuin.sokar.agent.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads an {@code agent.yaml} into an {@link AgentDefinition}.
 * <p>
 * Loaded as plain maps and mapped by hand, for the same two reasons as the project reader: binding
 * onto records would need reflection metadata in a native image, and would let a definition name
 * any class on the classpath.
 */
public final class AgentDefinitionReader {

    private AgentDefinitionReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a definition from a classpath resource.
     *
     * @param loader Loader of the agent's own module.
     * @param resource Resource path, for example {@code /agent/claude.yaml}.
     * @return The definition.
     * @throws AgentException If the resource is missing or unusable.
     */
    public static AgentDefinition fromResource(Class<?> loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AgentException("No agent definition at " + resource
                        + " in " + loader.getName());
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8), resource);
        } catch (IOException ex) {
            throw new AgentException("Cannot read " + resource, ex);
        }
    }

    /**
     * Reads a definition.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @return The definition.
     * @throws AgentException If the content does not describe a usable agent.
     */
    public static AgentDefinition read(Reader reader, String origin) {

        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);

        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new AgentException("Cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new AgentException(origin + " is empty or is not a YAML mapping");
        }

        final Map<?, ?> git = section(root, "git_identity", origin);
        final Map<?, ?> headless = section(root, "headless", origin);
        final Map<?, ?> session = optionalSection(root, "session");
        final Map<?, ?> provider = optionalSection(root, "provider");
        final Map<?, ?> install = optionalSection(root, "install");

        final boolean supportsResume = flag(session, "supports_resume");

        return new AgentDefinition(
                required(root, "name", origin),
                text(root.get("label"), required(root, "name", origin)),
                required(root, "binary", origin),
                new GitIdentity(required(git, "name", origin), required(git, "email", origin)),
                new HeadlessFlags(
                        required(headless, "prompt_flag", origin),
                        optional(headless, "model_flag"),
                        optional(headless, "max_turns_flag"),
                        optional(headless, "verbose_flag"),
                        strings(headless.get("output_format_flags"))),
                supportsResume,
                optional(session, "resume_flag"),
                map(provider.get("token_env")),
                optional(provider, "base_url_env"),
                strings(root.get("allowed_domains")),
                optional(install, "version"),
                artifacts(install.get("artifacts"), origin),
                strings(install.get("as_root")),
                strings(install.get("as_agent")));
    }

    private static List<InstallArtifact> artifacts(@Nullable Object value, String origin) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new AgentException(origin + ": 'install.artifacts' must be a list");
        }
        final List<InstallArtifact> result = new ArrayList<>();
        for (final Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new AgentException(origin + ": each install artifact must be a mapping");
            }
            result.add(new InstallArtifact(
                    required(map, "url", origin),
                    optional(map, "sha256"),
                    required(map, "target", origin),
                    map.get("mode") == null ? "0755" : String.valueOf(map.get("mode")),
                    Boolean.TRUE.equals(map.get("unverified")),
                    optional(map, "reason")));
        }
        return List.copyOf(result);
    }

    private static Map<?, ?> section(Map<?, ?> root, String name, String origin) {
        final Object value = root.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw new AgentException(origin + " has no '" + name + "' section");
        }
        return map;
    }

    private static Map<?, ?> optionalSection(Map<?, ?> root, String name) {
        return root.get(name) instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String required(Map<?, ?> section, String key, String origin) {
        final Object value = section.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new AgentException(origin + ": '" + key + "' is required");
        }
        return String.valueOf(value);
    }

    @Nullable
    private static String optional(Map<?, ?> section, String key) {
        final Object value = section.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String text(@Nullable Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean flag(Map<?, ?> section, String key) {
        return Boolean.TRUE.equals(section.get(key));
    }

    private static List<String> strings(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String single) {
            // A YAML block scalar is one string holding several lines. Splitting it here means an
            // install fragment can be written as a readable block rather than a list of lines.
            return single.lines().filter(line -> !line.isBlank()).map(String::stripTrailing).toList();
        }
        if (!(value instanceof List<?> list)) {
            throw new AgentException("Expected a list but found " + value.getClass().getSimpleName());
        }
        final List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return List.copyOf(result);
    }

    private static Map<String, String> map(@Nullable Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new AgentException("Expected a mapping but found "
                    + value.getClass().getSimpleName());
        }
        final Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }
}
