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
                        // Optional: an empty flag is how a definition says the prompt is
                        // positional, which several agents are.
                        text(optional(headless, "prompt_flag")),
                        optional(headless, "model_flag"),
                        optional(headless, "max_turns_flag"),
                        optional(headless, "verbose_flag"),
                        strings(headless.get("output_format_flags"))),
                supportsResume,
                optional(session, "resume_flag"),
                map(provider.get("token_env")),
                agentProvider(provider, origin),
                strings(root.get("allowed_domains")),
                strings(root.get("refused_domains")),
                optional(install, "version"),
                artifacts(install.get("artifacts"), origin),
                strings(install.get("as_root")),
                strings(install.get("as_agent")),
                packaged(install.get("packaged"), origin),
                optional(root, "config_dir"),
                // How this agent logs in, as arguments to its own binary. Absent for most: an
                // agent whose credential is an API key has nothing to log in to, and Sokar must
                // not guess a verb - hardcoding one agent's would be wrong for every other.
                strings(optionalSection(root, "login").get("arguments")));
    }

    /**
     * Reads the {@code provider} block.
     * <p>
     * Absent for an agent that cannot be redirected at a proxy. That is a real case rather than
     * an error - it means the agent has to be given its credential directly, which is a decision
     * the operator makes with {@code --credential-type direct}, not something this reader can fix.
     * <p>
     * What is <em>not</em> here any more is the provider's own half: upstream, header, prefix and
     * path. Those are declared once, in the provider's own file, and an agent that restated them
     * would be free to disagree with the agent next to it about the same provider.
     *
     * @param provider The provider block.
     * @param origin Where the definition came from, for error messages.
     * @return What the agent says about providers, or {@code null} when it declares none.
     */
    @Nullable
    private static AgentProvider agentProvider(Map<?, ?> provider, String origin) {
        if (provider.isEmpty()) {
            return null;
        }
        final String dialect = optional(provider, "dialect");
        if (dialect == null) {
            // A block with only 'token_env' describes an agent that takes its credential
            // directly and is not brokered at all - a real case, not a mistake. But a block
            // that says how to point the agent somewhere and then omits the dialect is a
            // mistake, and a silent one, so it is named.
            for (final String key : List.of("endpoint", "socket_env", "base_url_env", "default")) {
                if (provider.get(key) != null) {
                    throw new AgentException(origin + ": 'provider." + key + "' is set, so the"
                            + " block must also name the 'dialect' the agent speaks, or 'native'");
                }
            }
            return null;
        }
        return new AgentProvider(dialect, optional(provider, "default"),
                ProviderRoute.Endpoint.of(optional(provider, "endpoint"), origin),
                optional(provider, "socket_env"), optional(provider, "base_url_env"));
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static List<PackagedTree> packaged(@Nullable Object value, String origin) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new AgentException(origin + ": 'install.packaged' must be a list");
        }
        final List<PackagedTree> result = new ArrayList<>();
        for (final Object element : list) {
            if (!(element instanceof Map<?, ?> entry)) {
                throw new AgentException(origin + ": each 'install.packaged' entry must be a map");
            }
            result.add(new PackagedTree(required(entry, "source", origin),
                    required(entry, "target", origin)));
        }
        return List.copyOf(result);
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
