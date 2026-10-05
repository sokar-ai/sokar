package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Puts a person's own words into a task's inbox, where its agent already reads.
 * <p>
 * <strong>From a person at this machine, and nowhere else</strong> (decided by the operator on 2026-09-30): what a
 * person typed at their own machine, or through an interface to its daemon - never a peer's message, which reaches
 * the inbox only through the transport, the signature check and the filter. It carries {@link MessageSay#ROLE},
 * as everything a person writes does, and {@code from: person}, so an agent tells it from a peer's.
 * <p>
 * <strong>Written by the host into the task's half, like a bounce</strong>: through {@code tmp} and a rename, so the
 * agent never lists half a note. It never leaves the machine, so it is neither signed nor filtered.
 */
public final class PersonNote {

    /** Where a note says it came from. */
    public static final String FROM = "person";

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    /**
     * Writes one note into the task's inbox.
     *
     * @param mailbox The task's mailbox.
     * @param text What the person said, as the agent reads it.
     * @param data What the note is about, for an agent that reads the data part, or {@code null}.
     * @return The file name written.
     * @throws IOException Writing failed.
     */
    public String tell(final Mailbox mailbox, final String text, final @Nullable Map<String, Object> data)
            throws IOException {
        final Instant now = Instant.now();
        final String id = "person-" + UUID.randomUUID();
        final List<Object> parts = new ArrayList<>();
        parts.add(new LinkedHashMap<>(Map.of("text", text, "mediaType", "text/plain")));
        if (data != null && !data.isEmpty()) {
            parts.add(new LinkedHashMap<>(Map.of("data", data)));
        }
        final Map<String, Object> note = new LinkedHashMap<>();
        note.put("messageId", id);
        note.put("role", MessageSay.ROLE);
        note.put("contextId", "c-" + UUID.randomUUID());
        note.put("parts", parts);
        // Said to this task alone: it names it, so its agent at rest is told it is there.
        note.put("metadata", new LinkedHashMap<>(Map.of("from", FROM, "via", "direct")));

        final String name = FILE_TIME.format(now) + "--" + id + ".json";
        mailbox.refuseLinks();
        Files.createDirectories(mailbox.inboxTmp());
        Files.createDirectories(mailbox.inboxNew());
        final Path staged = mailbox.inboxTmp().resolve(name);
        Files.writeString(staged, Json.write(note), StandardCharsets.UTF_8);
        mailbox.intoInbox(staged);
        return name;
    }

    /**
     * Tells the task a rejected push came from, when it still has a mailbox.
     * <p>
     * A pending push is named after its task, so the task is found from the name; one that has been removed has no
     * inbox, and the answer says so rather than claiming it was told.
     *
     * @param context This machine.
     * @param project The project the push is pending in.
     * @param work The pending push's name.
     * @param reason What the person said, or {@code null}.
     * @return Whether a task was told.
     * @throws IOException Writing failed.
     */
    public static boolean rejectedWork(final SokarContext context,
            final org.fuin.sokar.core.project.Project project, final String work, final @Nullable String reason)
            throws IOException {
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(context.tasks().containerName(project, work)));
        if (!mailbox.exists()) {
            return false;
        }
        new PersonNote().rejected(mailbox, work, reason);
        return true;
    }

    /**
     * Tells a task that a person rejected the work it handed over, and why.
     *
     * @param mailbox The task's mailbox.
     * @param work The pending push's name, as the gate lists it.
     * @param reason What the person said, or {@code null}.
     * @return The file name written.
     * @throws IOException Writing failed.
     */
    public String rejected(final Mailbox mailbox, final String work, final @Nullable String reason)
            throws IOException {
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("verdict", "rejected");
        data.put("work", work);
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason);
        }
        return tell(mailbox, "A person rejected the work you handed over as '" + work + "'; it will not reach the"
                + " upstream." + (reason == null || reason.isBlank() ? "" : " They said: " + reason), data);
    }
}
