package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;

/**
 * What a task started on the host, recorded so the task can be resumed.
 * <p>
 * The credential proxy, the git gate and the clearance watcher are host processes, not part of the
 * container. Restarting the container alone gives back the workspace and nothing that makes it
 * usable, so what each was started with is written down while it is known - a resumed task cannot
 * reconstruct a gate port or an upstream from the container.
 * <p>
 * <strong>Owner-only.</strong> The gate's per-task token is in here, which is why this file is
 * written with the same permissions as the token file beside it.
 */
record TaskHelpers(List<Helper> helpers) {

    /** Schema version, so a file from an older Sokar is refused rather than half-understood. */
    static final int VERSION = 2;

    /** Name of the file in a task's state directory. */
    static final String FILE = "resume.json";

    /** A helper whose socket the container mounts, so it has to exist before the container. */
    static final String BEFORE = "before";

    /** A helper that needs the running container, such as anything given its pid. */
    static final String AFTER = "after";

    /**
     * One host process belonging to a task.
     *
     * @param name What it is, matching its pid file: {@code vault}, {@code gate}, {@code watcher}.
     * @param command Program and arguments, exactly as it was started.
     * @param environment Extra variables it needs, which may include a secret.
     * @param phase {@link #BEFORE} or {@link #AFTER}, relative to starting the container.
     */
    record Helper(String name, List<String> command, Map<String, String> environment,
            String phase) {
    }

    /**
     * Writes the record into a task's state directory.
     *
     * @param stateDirectory Where the task keeps its files.
     * @throws IOException If it cannot be written.
     */
    void writeTo(Path stateDirectory) throws IOException {
        final List<Object> entries = new ArrayList<>();
        for (final Helper helper : helpers) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", helper.name());
            entry.put("command", helper.command());
            entry.put("environment", helper.environment());
            entry.put("phase", helper.phase());
            entries.add(entry);
        }
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", VERSION);
        document.put("helpers", entries);

        final Path file = stateDirectory.resolve(FILE);
        Files.writeString(file, Json.write(document), StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * Reads what a task recorded, or an empty record when it recorded nothing.
     *
     * @param stateDirectory Where the task keeps its files.
     * @return The helpers, never {@code null}.
     */
    static TaskHelpers readFrom(Path stateDirectory) {
        final Path file = stateDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return new TaskHelpers(List.of());
        }
        try {
            if (!(Json.parse(Files.readString(file)) instanceof Map<?, ?> document)) {
                return new TaskHelpers(List.of());
            }
            if (!Integer.valueOf(VERSION).equals(number(document.get("version")))) {
                return new TaskHelpers(List.of());
            }
            final List<Helper> found = new ArrayList<>();
            if (document.get("helpers") instanceof List<?> entries) {
                for (final Object entry : entries) {
                    if (entry instanceof Map<?, ?> map) {
                        found.add(new Helper(String.valueOf(map.get("name")),
                                strings(map.get("command")), variables(map.get("environment")),
                                AFTER.equals(map.get("phase")) ? AFTER : BEFORE));
                    }
                }
            }
            return new TaskHelpers(List.copyOf(found));
        } catch (IOException | RuntimeException ex) {
            return new TaskHelpers(List.of());
        }
    }

    private static Integer number(Object value) {
        return value instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }

    private static List<String> strings(Object value) {
        final List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> result.add(String.valueOf(item)));
        }
        return List.copyOf(result);
    }

    private static Map<String, String> variables(Object value) {
        final Map<String, String> result = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        }
        return Map.copyOf(result);
    }
}
