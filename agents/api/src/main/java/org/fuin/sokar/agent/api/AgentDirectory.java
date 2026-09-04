package org.fuin.sokar.agent.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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
 */
public class AgentDirectory {

    /** Prefix an agent binary must carry to be considered. */
    public static final String PREFIX = "sokar-agent-";

    /** Where the agent packages install their binaries. */
    public static final Path PACKAGED = Path.of("/usr/libexec/sokar/agents");

    private final List<Path> locations;

    /**
     * Constructor.
     *
     * @param locations Directories to scan, most specific first.
     */
    public AgentDirectory(List<Path> locations) {
        this.locations = List.copyOf(locations);
    }

    /**
     * Returns the standard locations for a user.
     *
     * @param dataHome The user's XDG data directory.
     * @return Directories, the operator's own first.
     */
    public static AgentDirectory standard(Path dataHome) {
        return new AgentDirectory(List.of(dataHome.resolve("agents"), PACKAGED));
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
     * Finds the agent binaries that are installed.
     * <p>
     * The name must start with {@value #PREFIX} and the file must be executable. Both conditions
     * matter: a directory an operator can write to is not a place to execute whatever happens to
     * be lying in it.
     *
     * @return Executables, one per agent name, the earliest location winning.
     */
    public List<Path> executables() {
        return select(true);
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
