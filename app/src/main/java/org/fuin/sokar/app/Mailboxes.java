package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every mailbox on this machine.
 * <p>
 * One subscription over the daemon's socket carries all of them, so something has to be able to
 * name them all. They are found by looking rather than by keeping a list: a mailbox is a directory
 * made when a task is made, and a second list of them would be a second thing to keep in step.
 */
public final class Mailboxes {

    private final SokarPaths paths;

    /**
     * Constructor.
     *
     * @param paths Where this machine keeps things.
     */
    public Mailboxes(final SokarPaths paths) {
        this.paths = paths;
    }

    /**
     * Returns every mailbox, by the task it belongs to.
     *
     * @return Container name and mailbox, sorted by name.
     * @throws IOException Listing failed.
     */
    public List<Mailbox> all() throws IOException {
        final Path root = paths.mailbox("x").getParent();
        if (root == null || !Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(root)) {
            final List<Mailbox> found = new ArrayList<>();
            entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> found.add(new Mailbox(path)));
            return List.copyOf(found);
        }
    }
}
