package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.wire.Json;

/**
 * Where each project's file is, remembered when a task is started with it.
 * <p>
 * <strong>This exists because a remote client has no filesystem to look in.</strong> Every gate
 * method and {@code Start} take the path of a {@code project.yml}, which is fine for a CLI typed
 * on the machine that holds it and impossible for an interface reached over a forwarded socket:
 * there is nothing on that side to browse, and a path it invented would name a file on the wrong
 * computer. So the daemon remembers the ones it has been given.
 * <p>
 * Written on every task start rather than by a registration step nobody would run. A project
 * enters this the first time somebody runs a task with it, which is also the first moment it is
 * worth an interface knowing about.
 */
final class ProjectRegistry {

    private final Path file;

    /**
     * Constructor with the file to keep.
     *
     * @param file Where the registry is stored.
     */
    ProjectRegistry(Path file) {
        this.file = file;
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
        try {
            final Map<String, Object> known = new LinkedHashMap<>(all());
            if (projectFile.toString().equals(known.get(name))) {
                return;
            }
            known.put(name, projectFile.toString());
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // Written beside and moved, so a reader never sees half a document: two tasks can
            // start at the same moment and both write this.
            final Path temporary = file.resolveSibling(file.getFileName() + ".new");
            Files.writeString(temporary, Json.write(known), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING,
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
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        try {
            if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                    instanceof Map<?, ?> document)) {
                return Map.of();
            }
            final Map<String, String> known = new LinkedHashMap<>();
            document.forEach((name, path) ->
                    known.put(String.valueOf(name), String.valueOf(path)));
            return Map.copyOf(known);
        } catch (IOException | RuntimeException ex) {
            // A file somebody edited by hand, or a half-written one from an older version. An
            // empty answer costs a path that comes back on the next task start.
            return Map.of();
        }
    }
}
