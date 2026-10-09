package org.fuin.sokar.agent.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Where installed agent binaries are found.
 * <p>
 * A directory scan rather than a list, so that installing a package is all it takes for an
 * already-built Sokar to start using an agent. This is the whole point of shipping agents
 * separately: nothing here knows what it will find.
 * <p>
 * Two locations, checked in order. A file in the operator's own directory wins over a system one
 * of the same name, so an operator can try a build of an agent without root and without disturbing
 * the packaged one.
 * <p>
 * <strong>Or by a description.</strong> A vendor's package need not carry Sokar's name: it puts one file,
 * {@code <name>.yaml} with {@code executable: <absolute path>}, into {@code agents.d} - the account's own, then the
 * system's, the account's winning by name - and the agent is wherever the vendor installs it. The file names starting
 * with {@value #PREFIX} are still read, for the agent packages released before.
 */
public class AgentDirectory {

    /** Prefix an agent binary must carry to be considered. */
    public static final String PREFIX = "sokar-agent-";

    /** Where the agent packages install their binaries. */
    public static final Path PACKAGED = Path.of("/usr/libexec/sokar/agents");

    /** Where a package puts the description of an agent it installs under its own names. */
    public static final Path PACKAGED_DESCRIPTIONS = Path.of("/usr/share/sokar/agents.d");

    private final List<Path> locations;

    private final List<Path> descriptionLocations;

    /**
     * One description of an agent under {@code agents.d}.
     *
     * @param name The agent's name, the file's without {@code .yaml}.
     * @param file The description.
     * @param executable The executable it names, or {@code null} when it is refused.
     * @param refusal Why it is not taken, or {@code null}.
     */
    public record Description(String name, Path file, @Nullable Path executable, @Nullable String refusal) {
    }

    /**
     * Constructor.
     *
     * @param locations Directories to scan, most specific first.
     */
    public AgentDirectory(List<Path> locations) {
        this(locations, List.of());
    }

    /**
     * Constructor.
     *
     * @param locations Directories to scan for binaries, most specific first.
     * @param descriptionLocations Directories to read descriptions from, most specific first.
     */
    public AgentDirectory(List<Path> locations, List<Path> descriptionLocations) {
        this.locations = List.copyOf(locations);
        this.descriptionLocations = List.copyOf(descriptionLocations);
    }

    /**
     * Returns the standard locations for a user.
     *
     * @param dataHome The user's XDG data directory.
     * @return Directories, the operator's own first.
     */
    public static AgentDirectory standard(Path dataHome) {
        return new AgentDirectory(List.of(dataHome.resolve("agents"), PACKAGED),
                List.of(dataHome.resolve("agents.d"), PACKAGED_DESCRIPTIONS));
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
     * Returns the directories descriptions are read from.
     *
     * @return Locations, in order.
     */
    public List<Path> descriptionLocations() {
        return descriptionLocations;
    }

    /**
     * Finds the agent binaries that are installed.
     * <p>
     * The name must start with {@value #PREFIX} and the file must be executable. Both conditions
     * matter: a directory an operator can write to is not a place to execute whatever happens to
     * be lying in it.
     *
     * @return Executables, one per agent name, the earliest location winning.
     */
    public List<Path> executables() {
        final List<Path> found = new ArrayList<>(select(true));
        for (final Description description : descriptions()) {
            if (description.executable() != null && !found.contains(description.executable())) {
                found.add(description.executable());
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns the descriptions in use, one per name, the account's own first - those refused included, with why.
     *
     * @return Descriptions, in the order found.
     */
    public List<Description> descriptions() {
        final Map<String, Description> byName = new LinkedHashMap<>();
        for (final Path file : descriptionFiles()) {
            byName.putIfAbsent(nameOf(file), read(file));
        }
        return List.copyOf(byName.values());
    }

    /**
     * Returns each description that is never used, because the account's own of the same name hides it, with that one.
     * Reported by {@code sokar doctor}.
     *
     * @return Hidden description to the one in use, possibly empty.
     */
    public Map<Path, Path> hiddenDescriptions() {
        final Map<String, Path> first = new LinkedHashMap<>();
        final Map<Path, Path> hidden = new LinkedHashMap<>();
        for (final Path file : descriptionFiles()) {
            final Path winner = first.putIfAbsent(nameOf(file), file);
            if (winner != null) {
                hidden.put(file, winner);
            }
        }
        return java.util.Collections.unmodifiableMap(hidden);
    }

    private List<Path> descriptionFiles() {
        final List<Path> files = new ArrayList<>();
        for (final Path location : descriptionLocations) {
            if (!Files.isDirectory(location)) {
                continue;
            }
            try (Stream<Path> entries = Files.list(location)) {
                entries.filter(path -> path.getFileName().toString().endsWith(".yaml"))
                        .filter(Files::isRegularFile).sorted().forEach(files::add);
            } catch (IOException ex) {
                throw new AgentException("Cannot list " + location, ex);
            }
        }
        return files;
    }

    private static String nameOf(Path file) {
        final String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".yaml".length());
    }

    /**
     * Reads one description; never throws, since one vendor's broken file must not hide the other agents.
     */
    private static Description read(Path file) {
        final String name = nameOf(file);
        final Object loaded;
        try (java.io.Reader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            loaded = new org.yaml.snakeyaml.Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                    new org.yaml.snakeyaml.LoaderOptions())).load(reader);
        } catch (IOException | RuntimeException ex) {
            return new Description(name, file, null, "cannot be read: " + ex.getMessage());
        }
        if (!(loaded instanceof Map<?, ?> root) || !(root.get("executable") instanceof String named)
                || named.isBlank()) {
            return new Description(name, file, null, "names no 'executable'");
        }
        final Path executable = Path.of(named.strip());
        if (!executable.isAbsolute()) {
            return new Description(name, file, null, "'" + named + "' is not an absolute path");
        }
        if (!Files.isRegularFile(executable)) {
            return new Description(name, file, null, executable + " is not a regular file");
        }
        if (!Files.isExecutable(executable)) {
            return new Description(name, file, null, executable + " is not executable");
        }
        return new Description(name, file, executable, null);
    }

    /**
     * Finds agent binaries that are installed and never used, because an earlier location holds
     * one of the same name. Reported by {@code sokar doctor}.
     *
     * @return Executables that lose to an earlier location, possibly empty.
     */
    public List<Path> shadowed() {
        return select(false);
    }

    /**
     * Returns each installed binary that never runs, with the one that runs instead.
     * <p>
     * The pairing lives here rather than at the caller because the rule that decides it lives
     * here: a caller that matched winners to losers itself would be a second implementation of
     * "which copy is in use", and it would differ exactly when it mattered.
     *
     * @return Shadowed binary to the one shadowing it, in scan order, possibly empty.
     */
    public Map<Path, Path> shadowedBy() {

        final Map<String, Path> winners = new LinkedHashMap<>();
        for (final Path path : executables()) {
            winners.put(path.getFileName().toString(), path);
        }

        // Insertion order kept on purpose: Map.copyOf would lose it, and the order these were
        // found in is the only thing that explains why one wins.
        final Map<Path, Path> pairs = new LinkedHashMap<>();
        for (final Path loser : shadowed()) {
            final Path winner = winners.get(loser.getFileName().toString());
            if (winner != null) {
                pairs.put(loser, winner);
            }
        }
        return java.util.Collections.unmodifiableMap(pairs);
    }

    /**
     * Scans every location once and keeps either the winners or the losers.
     *
     * @param winners {@code true} for the binary that runs, {@code false} for the ones it hides.
     * @return Executables, in scan order.
     */
    private List<Path> select(boolean winners) {

        final List<Path> found = new ArrayList<>();
        final List<String> seen = new ArrayList<>();

        for (final Path location : locations) {
            for (final Path path : candidates(location)) {
                final String name = path.getFileName().toString();
                if (seen.contains(name)) {
                    if (!winners) {
                        found.add(path);
                    }
                } else {
                    seen.add(name);
                    if (winners) {
                        found.add(path);
                    }
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns the agent binaries in one directory.
     *
     * @param location Directory to scan.
     * @return Executables, sorted by name, empty when the directory does not exist.
     */
    private List<Path> candidates(Path location) {
        if (!Files.isDirectory(location)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(location)) {
            return entries.filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .filter(Files::isRegularFile)
                    .filter(Files::isExecutable)
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new AgentException("Cannot list " + location, ex);
        }
    }
}
