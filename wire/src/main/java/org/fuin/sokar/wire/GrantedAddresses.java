package org.fuin.sokar.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which address a task was let through to, and the name it was reached by.
 * <p>
 * <strong>Written at the one moment both are known.</strong> The clearance watcher sees a blocked
 * packet, looks the address up to a name, and lets it through; from then on the firewall holds an
 * address and nothing holds the pair. Taking a grant back needs it - a name has to become the
 * addresses to remove - and re-resolving the name later was rejected for a specific reason:
 * a CDN, GeoDNS or plain round-robin answers Sokar and the container differently, and the
 * addresses that differ are precisely the ones a withdrawal would leave open.
 * <p>
 * Append-only, beside the granted names, in the task's own state directory. It outlives a watcher
 * restart, which matters because a replayed decision carries no name.
 */
public final class GrantedAddresses {

    /** Name of the file in a task's state directory. */
    public static final String FILE = "granted-addresses";

    private GrantedAddresses() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Records that a name was reached at an address.
     *
     * @param stateDirectory Where the task keeps its files.
     * @param name Host name the grant was made for.
     * @param address Address the container was answered with.
     * @throws IOException If it cannot be written.
     */
    public static void add(Path stateDirectory, String name, String address) throws IOException {
        Files.writeString(stateDirectory.resolve(FILE),
                name.strip() + "\t" + address.strip() + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Returns every address recorded for one name.
     * <p>
     * Exact, never by suffix. A grant for {@code example.com} covers {@code api.example.com} when
     * deciding what to let through - that is what {@link GrantedNames} does - but a withdrawal
     * must not remove an address that was let through under a different name, which may still be
     * granted.
     *
     * @param stateDirectory Where the task keeps its files.
     * @param name Host name.
     * @return Addresses, without duplicates, in the order they were recorded.
     */
    public static List<String> forName(Path stateDirectory, String name) {
        final String wanted = name.strip();
        final Set<String> found = new LinkedHashSet<>();
        for (final String[] pair : all(stateDirectory)) {
            if (pair[0].equals(wanted)) {
                found.add(pair[1]);
            }
        }
        return List.copyOf(found);
    }

    /**
     * Returns every recorded pair.
     *
     * @param stateDirectory Where the task keeps its files.
     * @return Name and address per entry, empty when nothing was recorded.
     */
    public static List<String[]> all(Path stateDirectory) {
        final Path file = stateDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<String[]> pairs = new ArrayList<>();
        try {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String[] parts = line.split("\t", 2);
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    pairs.add(new String[] {parts[0].strip(), parts[1].strip()});
                }
            }
        } catch (IOException ex) {
            // An unreadable record costs a withdrawal its addresses, and the withdrawal says so.
            // Failing the whole call would leave the name resolving as well, which is worse.
            return List.of();
        }
        return List.copyOf(pairs);
    }
}
