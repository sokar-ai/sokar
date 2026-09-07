package org.fuin.sokar.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Names somebody granted to a task while it was running.
 * <p>
 * A project declares what its tasks may reach before they start. This is the other thing: an agent
 * an hour into a run needs a host nobody thought of, and somebody decides that this run may have
 * it. The grant belongs to the run - it is not in the project file, and the next task will ask
 * again - which is why it lives in the task's own directory and dies with it.
 * <p>
 * <strong>Appended, never rewritten.</strong> One short line per grant, opened for append: two
 * grants arriving at once cannot lose each other, and nothing has to read the file to write it.
 * The same lesson the project registry learned the hard way, applied before it could bite.
 * <p>
 * This file only says which names are <em>allowed to resolve and be reached</em>. Making that true
 * is two other things: a line in the resolver's servers file, and the clearance watcher letting
 * the first connection through instead of asking about it.
 */
public final class GrantedNames {

    /** Name of the file in a task's state directory. */
    public static final String FILE = "granted";

    private GrantedNames() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Records that a name was granted to this run.
     *
     * @param stateDirectory Where the task keeps its files.
     * @param name Host name, as it was granted.
     * @throws IOException If it cannot be written. Never swallowed: a grant nobody wrote down is a
     *         host the watcher will still ask about, which reads as the grant having been ignored.
     */
    public static void add(Path stateDirectory, String name) throws IOException {
        Files.writeString(stateDirectory.resolve(FILE), name.strip() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Returns the names granted to this run, in the order they were granted.
     *
     * @param stateDirectory Where the task keeps its files.
     * @return The names, without duplicates, empty when nothing was granted.
     */
    public static List<String> all(Path stateDirectory) {
        final Path file = stateDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            final Set<String> names = new LinkedHashSet<>();
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String name = line.strip();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
            return List.copyOf(names);
        } catch (IOException ex) {
            // An unreadable file costs the grants, and the watcher goes back to asking - which is
            // the safe direction to fail in.
            return List.of();
        }
    }

    /**
     * Tells whether a name is covered by the grants a task has.
     * <p>
     * A grant covers what is under it, because that is what the resolver does with it: dnsmasq
     * matches {@code server=/example.com/} for {@code cdn.example.com} too. A rule here that was
     * stricter than the resolver's would leave a name resolving and then blocked, which is the one
     * combination the whole design exists to avoid.
     *
     * @param stateDirectory Where the task keeps its files.
     * @param name Name to check, as the resolver logged it.
     * @return {@code true} when a grant covers it.
     */
    public static boolean covers(Path stateDirectory, String name) {
        final String wanted = name.strip().toLowerCase(java.util.Locale.ROOT);
        return all(stateDirectory).stream()
                .map(granted -> granted.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(granted -> wanted.equals(granted) || wanted.endsWith("." + granted));
    }
}
