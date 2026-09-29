package org.fuin.sokar.app;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.ProviderDefinition;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * A service a task's credential is for, when it is not a model provider: a forge, a search endpoint, an MCP
 * server.
 * <p>
 * <strong>A second kind, not a widened provider.</strong> A provider is which models it serves and in which
 * wire format; a forge or a search endpoint has no wire format in that sense, and widening the provider would
 * leave half its fields meaningless for half its instances. Decided 2026-09-09. What the two share is how a
 * key reaches the service - the header, the prefix, or the URL parameter - and that is all a destination
 * declares beside where it is.
 * <p>
 * A provider is then one kind of destination: a credential may name a provider, and it is reached as the
 * provider declares.
 *
 * @param name Short name, and the key a project and a run name it by.
 * @param label Human-readable name.
 * @param upstream Where requests go, https.
 * @param authHeader The header the key goes in.
 * @param authPrefix Text before the key in that header, for example {@code "Bearer "}.
 * @param authQuery The URL parameter the key goes in instead, or {@code null} for the header.
 */
public record Destination(String name, String label, String upstream, String authHeader, String authPrefix,
        @Nullable String authQuery) {

    /** Where a package installs destination declarations. */
    public static final Path PACKAGED = Path.of("/usr/share/sokar/destinations");

    /**
     * Constructor.
     *
     * @param name Short name.
     * @param label Human-readable name.
     * @param upstream Where requests go.
     * @param authHeader The header the key goes in.
     * @param authPrefix Text before the key.
     * @param authQuery The URL parameter instead, or {@code null}.
     */
    public Destination {
        if (!name.matches("[a-z0-9][a-z0-9-]{0,30}")) {
            // The name becomes part of a variable's name and reaches command lines.
            throw new IllegalArgumentException("Invalid destination name '" + name
                    + "', expected lower-case letters, digits and hyphens");
        }
        final URI uri = parsed(name, upstream);
        if (uri.getHost() == null || !"https".equals(uri.getScheme())) {
            // A key sent in the clear is a key given away, whatever the broker does before it.
            throw new IllegalArgumentException("Destination '" + name + "' must be reached over https, not '"
                    + upstream + "'");
        }
        if (authHeader.isBlank()) {
            throw new IllegalArgumentException("Destination '" + name + "' names no header for its key");
        }
    }

    private static URI parsed(String name, String upstream) {
        try {
            return URI.create(upstream);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Destination '" + name + "': '" + upstream + "' is not a URL", ex);
        }
    }

    /**
     * Returns the host the broker talks to.
     *
     * @return The host.
     */
    public String host() {
        return java.util.Objects.requireNonNull(URI.create(upstream).getHost());
    }

    /**
     * Returns a provider as the destination it also is, for a credential of the given kind.
     *
     * @param provider The provider.
     * @param credentialType The credential's kind.
     * @return The destination.
     */
    public static Destination of(ProviderDefinition provider, String credentialType) {
        return new Destination(provider.name(), provider.label(), provider.upstream(),
                provider.authHeaderFor(credentialType), provider.authPrefixFor(credentialType),
                provider.authQueryFor(credentialType));
    }

    /**
     * Reads a destination's declaration.
     *
     * @param reader The YAML.
     * @param origin Where it came from, for messages.
     * @return The destination.
     * @throws IllegalArgumentException If it declares no valid destination.
     */
    public static Destination read(Reader reader, String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(origin + " is empty or is not a YAML mapping");
        }
        final String name = text(root, "name");
        final String upstream = text(root, "upstream");
        if (name == null || upstream == null) {
            throw new IllegalArgumentException(origin + ": 'name' and 'upstream' are required");
        }
        final String label = text(root, "label");
        final String header = text(root, "auth_header");
        final String prefix = text(root, "auth_prefix");
        try {
            return new Destination(name, label == null ? name : label, upstream,
                    header == null ? "Authorization" : header, prefix == null ? "" : prefix, text(root, "auth_query"));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(origin + ": " + ex.getMessage(), ex);
        }
    }

    private static @Nullable String text(Map<?, ?> root, String key) {
        final Object value = root.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    /**
     * Finds every destination declared for one user, their own before the packaged ones.
     *
     * @param dataHome The user's data directory, {@code $XDG_DATA_HOME/sokar}.
     * @return By name; a name declared twice keeps its first.
     */
    public static Map<String, Destination> all(Path dataHome) {
        final Map<String, Destination> found = new LinkedHashMap<>();
        for (final Path location : List.of(dataHome.resolve("destinations"), PACKAGED)) {
            for (final Path file : files(location)) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    final Destination destination = read(reader, file.toString());
                    found.putIfAbsent(destination.name(), destination);
                } catch (IOException | IllegalArgumentException ex) {
                    // One unreadable declaration hides itself, never the others.
                }
            }
        }
        return found;
    }

    /**
     * Resolves what a credential names: a declared destination, or a provider as the destination it is.
     *
     * @param name What the credential names.
     * @param destinations The declared destinations.
     * @param providers The declared providers.
     * @param credentialType The credential's kind, for a provider's header.
     * @return The destination, or {@code null} when nothing by that name is declared.
     */
    public static @Nullable Destination resolve(String name, Map<String, Destination> destinations,
            Map<String, ProviderDefinition> providers, @Nullable String credentialType) {
        final Destination declared = destinations.get(name);
        if (declared != null) {
            return declared;
        }
        final ProviderDefinition provider = providers.get(name);
        return provider == null ? null
                : of(provider, credentialType == null ? AgentDefinition.DEFAULT_TOKEN_KEY : credentialType);
    }

    private static List<Path> files(Path location) {
        if (!Files.isDirectory(location)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(location)) {
            return new ArrayList<>(entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yaml")).sorted().toList());
        } catch (IOException ex) {
            return List.of();
        }
    }

    /**
     * Reads a declaration from text, for tests and for a declaration handed over whole.
     *
     * @param yaml The YAML.
     * @return The destination.
     */
    static Destination parse(String yaml) {
        return read(new StringReader(yaml), "text");
    }
}
