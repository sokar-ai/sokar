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
 * @param version Schema version, so a file from an older Sokar is recognized rather than
 *        half-understood.
 * @param agent Name of the agent running in the task, or {@code null} when it has none.
 * @param mode How a person is meant to be involved.
 * @param prompt What an unattended task was asked to do, or {@code null}. Kept after it finishes,
 *        because "what was this asked to do" is a question people have about a task that is over.
 * @param branch Ref the task's work goes to, or {@code null} when it has no gate.
 * @param startedAt When the task was started, ISO-8601.
 * @param clearance What the task does with a blocked connection: {@code prompt}, {@code allow},
 *        {@code deny} or {@code off}. The most consequential state a task can be in is {@code off}
 *        - nothing asks and nothing is refused - and until this was recorded it was invisible:
 *        nothing an interface listed could mark it, because nothing told the interface it was
 *        true.
 * @param label A changeable caption a person gave this task, or {@code null} when nobody has.
 *        <strong>Beside the identity, never instead of it.</strong> The task's name is what every
 *        other call takes and what somebody types into {@code sokar} at the machine, so a label
 *        that replaced it would make the interface and the command line disagree about what a
 *        thing is called. Absent and empty are deliberately the same state: both mean the row
 *        shows its real name, and a third would cost a value class to express a difference nobody
 *        can act on.
 * @param provider The model provider the task was brokered to, by the name {@code sokar providers}
 *        lists, or {@code null} when it was brokered to none. Settled once at start and asked about
 *        afterwards - "which service did this task's prompts go to" - when nothing else records it.
 */
public record TaskProfile(int version, @Nullable String agent, TaskMode mode,
        @Nullable String prompt, @Nullable String branch, String startedAt,
        @Nullable String clearance, @Nullable String label, @Nullable String provider) {

    /**
     * Constructor for a task that names no provider.
     *
     * @param version Schema version.
     * @param agent Agent running in the task, or {@code null}.
     * @param mode How a person is meant to be involved.
     * @param prompt What an unattended task was asked to do, or {@code null}.
     * @param branch Ref the work goes to, or {@code null}.
     * @param startedAt When it started, ISO-8601.
     * @param clearance What it does with a blocked connection.
     * @param label Its caption, or {@code null}.
     */
    public TaskProfile(int version, @Nullable String agent, TaskMode mode,
            @Nullable String prompt, @Nullable String branch, String startedAt,
            @Nullable String clearance, @Nullable String label) {
        this(version, agent, mode, prompt, branch, startedAt, clearance, label, null);
    }

    /**
     * Constructor for a task that has just started, which never has a label yet.
     *
     * @param version Schema version.
     * @param agent Agent running in the task, or {@code null}.
     * @param mode How a person is meant to be involved.
     * @param prompt What an unattended task was asked to do, or {@code null}.
     * @param branch Ref the work goes to, or {@code null}.
     * @param startedAt When it started, ISO-8601.
     * @param clearance What it does with a blocked connection.
     */
    public TaskProfile(int version, @Nullable String agent, TaskMode mode,
            @Nullable String prompt, @Nullable String branch, String startedAt,
            @Nullable String clearance) {
        this(version, agent, mode, prompt, branch, startedAt, clearance, null, null);
    }

    /**
     * Returns this profile with a different label.
     *
     * @param caption The new caption, or {@code null} to remove it. Blank counts as removing it:
     *        a label of spaces is a row with an invisible caption and no way to tell it from one
     *        that was never set.
     * @return A copy.
     */
    public TaskProfile withLabel(@Nullable String caption) {
        return new TaskProfile(version, agent, mode, prompt, branch, startedAt, clearance,
                caption == null || caption.isBlank() ? null : caption.strip(), provider);
    }

    /**
     * Returns a copy recording a different clearance mode.
     * <p>
     * Written when enforcement is changed on a task that is already running, so that what a task
     * reports is what it is doing rather than what it was started with. A field that kept saying
     * "prompt" for a task nobody is being asked about would be worse than not having it.
     *
     * @param mode prompt, allow, deny or off.
     * @return A copy.
     */
    public TaskProfile withClearance(String mode) {
        return new TaskProfile(version, agent, this.mode, prompt, branch, startedAt, mode, label, provider);
    }

    /** Current schema version. Bumped to 3 when a label could be given, to 4 when the provider was recorded. */
    public static final int VERSION = 4;

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
        document.put("clearance", clearance == null ? "" : clearance);
        document.put("label", label == null ? "" : label);
        document.put("provider", provider == null ? "" : provider);
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
                            : String.valueOf(document.get("startedAt")),
                    text(document, "clearance"),
                    // A profile from before labels existed has no key at all, and reads as
                    // unlabeled - which is the same state as a label somebody cleared, on
                    // purpose.
                    text(document, "label"),
                    // A task started before the provider was recorded reads as brokered to none, which is
                    // what an interface shows for "not known" as well.
                    text(document, "provider"));
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
