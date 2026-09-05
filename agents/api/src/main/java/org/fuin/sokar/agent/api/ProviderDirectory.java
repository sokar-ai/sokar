package org.fuin.sokar.agent.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where provider declarations are found.
 * <p>
 * A directory scan rather than a list, so a provider can be added without changing or rebuilding
 * anything - which is the point of declaring providers separately at all.
 * <p>
 * Two locations, checked in order, matching {@link AgentDirectory}. A declaration in the
 * operator's own directory wins over a packaged one of the same name, so a provider can be tried
 * without root. Unlike agents these are data files, not executables, so they live under
 * {@code share} rather than {@code libexec}.
 */
public class ProviderDirectory {

    /** Where packages install provider declarations. */
    public static final Path PACKAGED = Path.of("/usr/share/sokar/providers");

    /** Suffix a declaration must carry to be considered. */
    public static final String SUFFIX = ".yaml";

    private final List<Path> locations;

    /**
     * Constructor.
     *
     * @param locations Directories to scan, most specific first.
     */
    public ProviderDirectory(List<Path> locations) {
        this.locations = List.copyOf(locations);
    }

    /**
     * Returns the standard locations for a user.
     *
     * @param dataHome The user's XDG data directory.
     * @return Directories, the operator's own first.
     */
    public static ProviderDirectory standard(Path dataHome) {
        return new ProviderDirectory(List.of(dataHome.resolve("providers"), PACKAGED));
    }

    /**
     * Returns the directories being scanned.
     *
     * @return Locations, in order.
     */
    public List<Path> locations() {
        return locations;
    }

    /**
     * Finds every declared provider.
     * <p>
     * A declaration that cannot be read is skipped rather than fatal: one broken file an operator
     * dropped in must not stop every other provider from working. It is not silent either -
     * {@link #unreadable()} is what reports it.
     *
     * @return Providers by name, the operator's own winning over a packaged one.
     */
    public Map<String, ProviderDefinition> all() {
        final Map<String, ProviderDefinition> found = new LinkedHashMap<>();
        for (final Path file : declarations()) {
            try {
                final ProviderDefinition provider = ProviderDefinitionReader.fromFile(file);
                found.putIfAbsent(provider.name(), provider);
            } catch (AgentException ex) {
                continue;
            }
        }
        return found;
    }

    /**
     * Returns the declarations that could not be read, with the reason.
     *
     * @return File to reason, empty when everything parsed.
     */
    public Map<Path, String> unreadable() {
        final Map<Path, String> broken = new LinkedHashMap<>();
        for (final Path file : declarations()) {
            try {
                ProviderDefinitionReader.fromFile(file);
            } catch (AgentException ex) {
                broken.put(file, ex.getMessage() == null ? ex.toString() : ex.getMessage());
            }
        }
        return broken;
    }

    /**
     * Returns the declaration files that would be read, in scan order.
     *
     * @return Files, possibly empty.
     */
    public List<Path> declarations() {
        final List<Path> all = new ArrayList<>();
        locations.forEach(location -> all.addAll(files(location)));
        return List.copyOf(all);
    }

    private static List<Path> files(Path location) {
        if (!Files.isDirectory(location)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(location)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(SUFFIX))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            return List.of();
        }
    }
}
