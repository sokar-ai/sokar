package org.fuin.sokar.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What a task is, written down when it starts, for anything that asks about it later.
 * <p>
 * The container knows it is up and the runtime knows since when; only the launcher knows which
 * agent it started, how somebody is meant to be involved, what the agent was asked to do and which
 * ref its work goes to. None of that can be recovered afterwards - an interface asking "which
 * agent is this?" was reduced to guessing - so it is recorded at the one moment it is known.
 * <p>
 * <strong>Not the sidecar.</strong> That file is the contract between the launcher and the three
 * hook binaries, which are installed once and outlive any release of the CLI; adding operator-
 * facing fields to it would make every one of them a protocol change. This file has no such
 * readers.
 *
 * @param version Schema version, so a file from an older Sokar is recognised rather than
 *        half-understood.
 * @param agent Name of the agent running in the task, or {@code null} when it has none.
 * @param mode How a person is meant to be involved.
 * @param prompt What an unattended task was asked to do, or {@code null}. Kept after it finishes,
 *        because "what was this asked to do" is a question people have about a task that is over.
 * @param branch Ref the task's work goes to, or {@code null} when it has no gate.
 * @param startedAt When the task was started, ISO-8601.
 */
public record TaskProfile(int version, @Nullable String agent, TaskMode mode,
        @Nullable String prompt, @Nullable String branch, String startedAt) {

    /** Current schema version. */
    public static final int VERSION = 1;

    /** Name of the file in a task's state directory. */
    public static final String FILE = "task.json";

    /**
     * Writes the profile into a task's state directory.
     *
     * @param stateDirectory Where the task keeps its files.
     * @throws IOException If it cannot be written.
     */
    public void writeTo(Path stateDirectory) throws IOException {
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", version);
        document.put("agent", agent == null ? "" : agent);
        document.put("mode", mode.wire());
        document.put("prompt", prompt == null ? "" : prompt);
        document.put("branch", branch == null ? "" : branch);
        document.put("startedAt", startedAt);
        final Path file = stateDirectory.resolve(FILE);
        Files.writeString(file, Json.write(document), StandardCharsets.UTF_8);
        // A prompt is the operator's own words and may name anything they were working on; the
        // same permissions as the other files here.
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
    }

    /**
     * Reads what a task recorded about itself.
     *
     * @param stateDirectory Where the task keeps its files.
     * @return The profile, or {@code null} when there is none - a task from an older Sokar, or one
     *         whose state directory has been removed.
     */
    @Nullable
    public static TaskProfile readFrom(Path stateDirectory) {
        final Path file = stateDirectory.resolve(FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (!(Json.parse(Files.readString(file, StandardCharsets.UTF_8))
                    instanceof Map<?, ?> document)) {
                return null;
            }
            return new TaskProfile(
                    document.get("version") instanceof Number number ? number.intValue() : 0,
                    text(document, "agent"),
                    TaskMode.parse(String.valueOf(document.get("mode")), TaskMode.SHELL),
                    text(document, "prompt"),
                    text(document, "branch"),
                    document.get("startedAt") == null ? ""
                            : String.valueOf(document.get("startedAt")));
        } catch (IOException | RuntimeException ex) {
            // A half-written file from a start that was killed. A task nobody can describe is
            // still a task somebody may need to stop.
            return null;
        }
    }

    @Nullable
    private static String text(Map<?, ?> document, String key) {
        final Object value = document.get(key);
        final String text = value == null ? "" : String.valueOf(value);
        return text.isEmpty() ? null : text;
    }
}
