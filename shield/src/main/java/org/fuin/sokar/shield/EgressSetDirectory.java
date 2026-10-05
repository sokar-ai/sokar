package org.fuin.sokar.shield;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Where curated egress sets are found.
 * <p>
 * A directory scan rather than a list, matching {@code ProviderDirectory}: a set can be added or
 * corrected without changing or rebuilding anything, which is the point of curating them
 * centrally at all. Two locations, the operator's own winning over a packaged one of the same
 * name, so a set can be tried without root.
 * <p>
 * A file that cannot be read is skipped rather than fatal, but a project asking for a set by name
 * and not getting it is an error - see {@link #resolve}. Those are different failures: one broken
 * file an operator dropped in must not stop everything else, while a project that believes it
 * declared Maven and silently reaches nothing is the failure this whole area exists to avoid.
 */
public class EgressSetDirectory {

    /** Where packages install curated sets. */
    public static final Path PACKAGED = Path.of("/usr/share/sokar/egress");

    /** Suffix a set file must carry to be considered. */
    public static final String SUFFIX = ".yaml";

    private final List<Path> locations;

    /**
     * Constructor.
     *
     * @param locations Directories to scan, most specific first.
     */
    public EgressSetDirectory(List<Path> locations) {
        this.locations = List.copyOf(locations);
    }

    /**
     * Returns the standard locations for a user.
     *
     * @param dataHome The user's XDG data directory.
     * @return Directories, the operator's own first.
     */
    public static EgressSetDirectory standard(Path dataHome) {
        return new EgressSetDirectory(List.of(dataHome.resolve("egress"), PACKAGED));
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
     * Finds every set that can be read.
     *
     * @return Sets by name, the operator's own winning over a packaged one.
     */
    public Map<String, EgressSet> all() {
        final Map<String, EgressSet> found = new LinkedHashMap<>();
        for (final Path file : files()) {
            try (Reader reader = Files.newBufferedReader(file)) {
                final EgressSet set = read(reader, file.toString());
                found.putIfAbsent(set.name(), set);
            } catch (IOException | RuntimeException ex) {
                continue;
            }
        }
        return found;
    }

    /**
     * Turns the names a project declared into the hosts they grant.
     * <p>
     * An unknown name is refused rather than ignored. A typo that silently granted nothing would
     * leave a project believing it had declared something, and the resulting failure - a
     * dependency that will not resolve - points at the build rather than at the project file.
     *
     * @param names Set names as written in the project file.
     * @return Hosts, in the order the sets and their entries were declared, without duplicates.
     * @throws EgressSetException If a name matches no set.
     */
    public List<String> resolve(List<String> names) {
        final Map<String, EgressSet> available = all();
        final List<String> domains = new ArrayList<>();
        for (final String name : names) {
            final EgressSet set = available.get(name);
            if (set == null) {
                throw new EgressSetException("Unknown egress set '" + name + "'. Available: "
                        + (available.isEmpty() ? "(none installed)"
                                : String.join(", ", available.keySet())));
            }
            for (final String domain : set.domains()) {
                if (!domains.contains(domain)) {
                    domains.add(domain);
                }
            }
        }
        return List.copyOf(domains);
    }

    /**
     * Returns the set each host came from, for reporting what a task may reach.
     *
     * @param names Set names as written in the project file.
     * @return Host to the set that granted it, first set winning.
     * @throws EgressSetException If a name matches no set.
     */
    public Map<String, String> origins(List<String> names) {
        final Map<String, EgressSet> available = all();
        final Map<String, String> origins = new LinkedHashMap<>();
        for (final String name : names) {
            final EgressSet set = available.get(name);
            if (set == null) {
                throw new EgressSetException("Unknown egress set '" + name + "'");
            }
            set.domains().forEach(domain -> origins.putIfAbsent(domain, set.name()));
        }
        return origins;
    }

    /**
     * Reads one set declaration.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @return The set.
     * @throws EgressSetException If the content does not describe a usable set.
     */
    public static EgressSet read(Reader reader, String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new EgressSetException("Cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new EgressSetException(origin + " is empty or is not a YAML mapping");
        }
        final String name = text(root.get("name"));
        if (name.isEmpty()) {
            throw new EgressSetException(origin + " has no 'name'");
        }
        if (!(root.get("domains") instanceof List<?> list) || list.isEmpty()) {
            throw new EgressSetException(origin + ": 'domains' must be a non-empty list");
        }
        final List<String> domains = new ArrayList<>();
        for (final Object entry : list) {
            final String domain = text(entry);
            if (domain.isEmpty()) {
                throw new EgressSetException(origin + ": 'domains' has an entry that is not a name");
            }
            if (!org.fuin.sokar.core.project.Egress.isDomain(domain)) {
                // Each one becomes resolver configuration: '#' there is every name, a newline is a line of its own.
                throw new EgressSetException(origin + ": '" + domain + "' is not a host name (lower case,"
                        + " letters, digits, '-' and '.', at least two labels)");
            }
            domains.add(domain);
        }
        final String label = text(root.get("label"));
        return new EgressSet(name, label.isEmpty() ? name : label, domains);
    }

    private static String text(@Nullable Object value) {
        return value == null ? "" : value.toString().strip();
    }

    private List<Path> files() {
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
