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

/**
 * Tells a task, in its own inbox, that something it sent did not go out.
 * <p>
 * The filter already answers what it refuses. This is for the refusals the filter never sees: a peer
 * a person has held, a mode that says nothing leaves unread, a transport that gave up for good. An
 * agent that gets no answer either waits forever or sends again, and both are worse than a refusal
 * it can read.
 * <p>
 * <strong>It is written in the same shape the filter's answers have</strong>, so the agent needs no
 * second format for the same event, and it is written into {@code feedback/} so it reaches the inbox
 * the way every other answer does - around the filter, because it never left the machine.
 */
public final class HostBounce {

    /** What writes it, in the answer's data part, so a reader can tell it from the filter's. */
    public static final String TOOL = "sokar";

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    /**
     * Writes one answer.
     *
     * @param mailbox The task's mailbox.
     * @param message The message that did not go out, wherever it now lies.
     * @param reason Why, in words the agent can act on.
     * @param settled Whether nothing will change: {@code true} for a refusal, {@code false} for
     *        something that is waiting and may still go.
     * @return The file name written.
     * @throws IOException Writing failed.
     */
    public String write(final Mailbox mailbox, final Path message, final String reason,
            final boolean settled) throws IOException {
        final Instant now = Instant.now();
        final String id = "sokar-" + UUID.randomUUID();
        final Map<String, Object> data = new LinkedHashMap<>();
        data.put("verdict", settled ? "refused" : "waiting");
        data.put("reason", reason);
        data.put("checkedAt", now.toString());
        data.put("tool", new LinkedHashMap<>(Map.of("name", TOOL)));
        final String about = idOf(message);
        if (!about.isEmpty()) {
            data.put(settled ? "rejectedMessageId" : "messageId", about);
        }

        final List<Object> parts = new ArrayList<>();
        parts.add(new LinkedHashMap<>(Map.of("text", reason, "mediaType", "text/plain")));
        parts.add(new LinkedHashMap<>(Map.of("data", data)));
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("messageId", id);
        answer.put("role", "ROLE_AGENT");
        final String context = contextOf(message);
        if (!context.isEmpty()) {
            answer.put("contextId", context);
        }
        answer.put("parts", parts);
        if (!about.isEmpty()) {
            answer.put("metadata", new LinkedHashMap<>(Map.of("inReplyToMessageId", about)));
        }

        final String name = FILE_TIME.format(now) + "--" + id + ".json";
        // Written beside and renamed in, like everything else that appears in a directory somebody
        // else is listing: half an answer is worse than none.
        final Path staged = mailbox.feedback().resolve("." + name);
        Files.writeString(staged, Json.write(answer), StandardCharsets.UTF_8);
        Files.move(staged, mailbox.feedback().resolve(name), StandardCopyOption.ATOMIC_MOVE);
        return name;
    }

    private String idOf(final Path message) {
        try {
            return Files.isRegularFile(message) ? MessageFile.id(message) : "";
        } catch (final IOException | RuntimeException ex) {
            // A message nobody can read is exactly the case where an answer still has to reach the
            // sender; it just cannot say which message it is about.
            return "";
        }
    }

    private String contextOf(final Path message) {
        try {
            if (Files.isRegularFile(message)
                    && Json.parse(Files.readString(message, StandardCharsets.UTF_8))
                            instanceof Map<?, ?> document
                    && document.get("contextId") instanceof String context) {
                return context;
            }
        } catch (final IOException | RuntimeException ex) {
            return "";
        }
        return "";
    }
}
