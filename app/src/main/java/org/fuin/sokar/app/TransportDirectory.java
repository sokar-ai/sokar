package org.fuin.sokar.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The transports installed on this machine.
 * <p>
 * A transport carries a message from one task's mailbox to another's. It is a package rather than
 * something Sokar knows about: the same shape agents already have, for the same reason - what a
 * transport is called, what it can do and what it reaches is the transport's own business, and
 * Sokar branches on what it answers rather than on its name.
 * <p>
 * <strong>Found by name and executed, never read.</strong> A directory an operator can write to is
 * not a place to run whatever happens to be lying in it, so a candidate has to carry the prefix, be
 * a regular file, and be executable - the same three tests the agent directory makes.
 */
public class TransportDirectory {

    /** What an adapter's file name starts with. */
    public static final String PREFIX = "sokar-message-transport-";

    /** Where packages install adapters. */
    public static final Path PACKAGED = Path.of("/usr/libexec/sokar/transports");

    private final List<Path> locations;

    /**
     * Constructor with the directories to look in, nearest first.
     *
     * @param locations Directories, which need not exist.
     */
    public TransportDirectory(final List<Path> locations) {
        this.locations = List.copyOf(locations);
    }

    /**
     * Returns the directory for one user, their own adapters before the packaged ones.
     *
     * @param dataHome The user's data directory.
     * @return Directory.
     */
    public static TransportDirectory standard(final Path dataHome) {
        return new TransportDirectory(List.of(dataHome.resolve("transports"), PACKAGED));
    }

    /**
     * Returns the directories this looks in.
     *
     * @return Directories, nearest first.
     */
    public List<Path> locations() {
        return locations;
    }

    /**
     * Returns every adapter, by the name that follows the prefix.
     * <p>
     * An adapter in a nearer directory shadows a packaged one of the same name, so an operator can
     * try one of their own without removing what a package installed.
     *
     * @return Name to executable, ordered by name.
     */
    public Map<String, Path> byName() {
        final Map<String, Path> found = new LinkedHashMap<>();
        for (final Path location : locations) {
            for (final Path candidate : candidates(location)) {
                found.putIfAbsent(name(candidate), candidate);
            }
        }
        final Map<String, Path> ordered = new LinkedHashMap<>();
        found.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    /**
     * Returns the adapter for one transport.
     *
     * @param transport Name after the prefix, as {@link #byName()} reports it.
     * @return The executable, or {@code null} when this machine has no such transport.
     */
    public Path find(final String transport) {
        return byName().get(transport);
    }

    /**
     * Returns the name of a transport from its file name.
     *
     * @param executable The adapter.
     * @return Name after the prefix.
     */
    public static String name(final Path executable) {
        return executable.getFileName().toString().substring(PREFIX.length());
    }

    private List<Path> candidates(final Path location) {
        if (!Files.isDirectory(location)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(location)) {
            final List<Path> candidates = new ArrayList<>(entries
                    .filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .filter(Files::isRegularFile)
                    // An adapter is executed. A file somebody dropped here without meaning it to
                    // run is not one, and neither is a stray note or a leftover archive.
                    .filter(Files::isExecutable).toList());
            candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return candidates;
        } catch (final IOException e) {
            throw new UncheckedIOException("Cannot read the transport directory " + location, e);
        }
    }
}
