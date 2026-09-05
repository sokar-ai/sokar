package org.fuin.sokar.agent.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads a provider declaration into a {@link ProviderDefinition}.
 * <p>
 * Loaded as plain maps and mapped by hand, for the same reason as the agent reader: binding onto
 * records would need reflection metadata in a native image, and would let a declaration name any
 * class on the classpath.
 */
public final class ProviderDefinitionReader {

    private ProviderDefinitionReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a declaration from a file.
     *
     * @param file The file.
     * @return The definition.
     * @throws AgentException If the file is missing or unusable.
     */
    public static ProviderDefinition fromFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return read(reader, file.toString());
        } catch (IOException ex) {
            throw new AgentException("Cannot read " + file, ex);
        }
    }

    /**
     * Reads a declaration from a classpath resource.
     *
     * @param loader Loader of the module carrying the resource.
     * @param resource Resource path, for example {@code /providers/anthropic.yaml}.
     * @return The definition.
     * @throws AgentException If the resource is missing or unusable.
     */
    public static ProviderDefinition fromResource(Class<?> loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AgentException("No provider declaration at " + resource
                        + " in " + loader.getName());
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8), resource);
        } catch (IOException ex) {
            throw new AgentException("Cannot read " + resource, ex);
        }
    }

    /**
     * Reads a declaration.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @return The definition.
     * @throws AgentException If the content does not describe a usable provider.
     */
    public static ProviderDefinition read(Reader reader, String origin) {

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

        final String name = required(root, "name", origin);
        return new ProviderDefinition(name,
                root.get("label") == null ? name : String.valueOf(root.get("label")),
                required(root, "upstream", origin),
                // Ordered, because the dialect a provider declares first is the one it is, and
                // that is what an agent asking for 'native' gets.
                ordered(root.get("dialects"), origin),
                ordered(root.get("auth_header"), origin),
                ordered(root.get("auth_prefix"), origin),
                ordered(root.get("unbrokerable"), origin),
                ordered(root.get("token_env"), origin));
    }

    private static String required(Map<?, ?> section, String key, String origin) {
        final Object value = section.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new AgentException(origin + ": '" + key + "' is required");
        }
        return String.valueOf(value);
    }

    private static Map<String, String> ordered(@Nullable Object value, String origin) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new AgentException(origin + ": expected a mapping but found "
                    + value.getClass().getSimpleName());
        }
        final Map<String, String> result = new LinkedHashMap<>();
        // Not Map.copyOf: that loses insertion order, and the first dialect is meaningful.
        source.forEach((key, item) -> result.put(String.valueOf(key),
                item == null ? "" : String.valueOf(item)));
        return result;
    }
}
