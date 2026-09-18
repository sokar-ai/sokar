package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fuin.sokar.wire.Json;

/**
 * Writes a person's own message into a task's outbox.
 * <p>
 * One place, used by the command line and by the daemon, so that a message a person writes at the
 * machine and one they write through an interface are the same message - same shape, same role,
 * same route out.
 * <p>
 * <strong>It goes into the outbox, not into a queue.</strong> A person is not a reason to skip the
 * filter; what differs from an agent's message is {@code role}, which is how the reader can tell a
 * person wrote it.
 */
public final class MessageSay {

    /** What a message from a person carries, where an agent's carries {@code ROLE_AGENT}. */
    public static final String ROLE = "ROLE_USER";

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    /**
     * Writes one message.
     *
     * @param mailbox The task's mailbox.
     * @param peer Who it is addressed to.
     * @param kind question, answer, status, review-request or handover.
     * @param contextId The conversation, or {@code null} for a new one.
     * @param text What it says.
     * @return The file name written.
     * @throws IOException Writing failed.
     */
    public String write(final Mailbox mailbox, final String peer, final String kind,
            final @org.jspecify.annotations.Nullable String contextId, final String text)
            throws IOException {
        final Instant now = Instant.now();
        final String id = "person-" + UUID.randomUUID();
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", id);
        message.put("role", ROLE);
        message.put("contextId", contextId == null || contextId.isBlank()
                ? "c-" + UUID.randomUUID() : contextId);
        message.put("parts",
                List.of(new LinkedHashMap<>(Map.of("text", text, "mediaType", "text/plain"))));
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", kind == null || kind.isBlank() ? "question" : kind);
        metadata.put("to", peer);
        message.put("metadata", metadata);

        final String name = FILE_TIME.format(now) + "--" + id + ".json";
        // Into the outbox through tmp and a rename, exactly as an agent has to write: a message is
        // complete when it is renamed, and nothing here gets a shortcut the agent does not have.
        final Path staged = mailbox.outboxTmp().resolve(name);
        Files.writeString(staged, Json.write(message), StandardCharsets.UTF_8);
        Files.move(staged, mailbox.outboxNew().resolve(name), StandardCopyOption.ATOMIC_MOVE);
        return name;
    }
}
