package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where each project's file is, remembered when a task is started with it.
 * <p>
 * <strong>This exists because a remote client has no filesystem to look in.</strong> Every gate
 * method and {@code Start} take the path of a {@code project.yml}, which is fine for a CLI typed
 * on the machine that holds it and impossible for an interface reached over a forwarded socket:
 * there is nothing on that side to browse, and a path it invented would name a file on the wrong
 * computer. So the daemon remembers the ones it has been given.
 * <p>
 * <strong>One file per project, not one document.</strong> A single JSON file has to be read,
 * changed and written back, and two tasks starting at the same moment then race: one writer's
 * changes are lost, and - measured as a defect in the first version of this class - both wrote
 * through the same temporary file, so the document moved into place could be a mixture of the two.
 * A file per project removes the class of problem rather than guarding it: two starts of different
 * projects never touch the same file, and two starts of the same project write the same bytes.
 * <p>
 * Written on every task start rather than by a registration step nobody would run.
 */
final class ProjectRegistry {

    private final Path directory;

    /**
     * Constructor with the directory to keep.
     *
     * @param directory Where one file per project is written.
     */
    ProjectRegistry(Path directory) {
        this.directory = directory;
    }

    /**
     * Records where a project's file is, replacing any earlier answer for that name.
     * <p>
     * Never fails a task. Losing this costs an interface a path it can rediscover the next time a
     * task runs, which is not worth failing a run that is otherwise fine.
     *
     * @param name Project name.
     * @param projectFile Absolute path of its file.
     */
    void remember(String name, Path projectFile) {
        if (!safe(name)) {
            return;
        }
        try {
            Files.createDirectories(directory);
            final Path entry = directory.resolve(name);
            if (Files.isRegularFile(entry)
                    && projectFile.toString().equals(Files.readString(entry).strip())) {
                return;
            }
            // A temporary name of its own, then a move: a shared one is what let two writers
            // interleave into the same bytes. The move is what a reader either sees or does not.
            final Path temporary = Files.createTempFile(directory, name, ".new");
            Files.writeString(temporary, projectFile + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.move(temporary, entry, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException ex) {
            return;
        }
    }

    /**
     * Returns every project file this machine has been told about.
     *
     * @return Project name to path, empty when nothing has been recorded.
     */
    Map<String, String> all() {
        if (!Files.isDirectory(directory)) {
            return Map.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            final Map<String, String> known = new LinkedHashMap<>();
            entries.filter(Files::isRegularFile)
                    // A writer's temporary file is not an entry, and it exists for microseconds.
                    .filter(entry -> !entry.getFileName().toString().endsWith(".new"))
                    .sorted()
                    .forEach(entry -> read(entry, known));
            return Map.copyOf(known);
        } catch (IOException ex) {
            return Map.of();
        }
    }

    private static void read(Path entry, Map<String, String> known) {
        try {
            final String path = Files.readString(entry, StandardCharsets.UTF_8).strip();
            if (!path.isEmpty()) {
                known.put(entry.getFileName().toString(), path);
            }
        } catch (IOException ex) {
            // One unreadable entry costs one path, not the whole listing.
            return;
        }
    }

    /**
     * Tells whether a name can be a file name here.
     * <p>
     * A project name is already validated where it is read, and this is the second place that
     * would have to be wrong for a name to escape the directory. Cheap enough to keep both.
     *
     * @param name Project name.
     * @return {@code true} when it names a file and nothing else.
     */
    private static boolean safe(String name) {
        return !name.isEmpty() && !name.contains("/") && !name.contains("\\")
                && !name.equals(".") && !name.equals("..");
    }
}
