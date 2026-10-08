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
 * <strong>It goes through the filter like any other message</strong> - a person is not a reason to
 * skip the check that a message carries nothing it should not.
 * <p>
 * <strong>But not through the container's outbox.</strong> It is written where only the host can
 * write, because {@code ROLE_USER} is a claim the filter accepts on this host's word: a message a
 * person wrote must be one a task could not have written.
 */
public final class MessageSay {

    /** What a message from a person carries, where an agent's carries {@code ROLE_AGENT}. */
    public static final String ROLE = "ROLE_USER";

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    /**
     * Says why a person's message to a name cannot go anywhere, or nothing when it can.
     * <p>
     * In a conversation any name may be a task of the project elsewhere, so none is refused here. Without one, only
     * a declared peer - or a task of the project on this machine, which the peers include - can be reached; anything
     * else was written, answered "written", and went nowhere.
     *
     * @param mail The project's peers, with its tasks on this machine.
     * @param peer Who it is for.
     * @return The reason, or {@code null} when it can go.
     */
    public static @org.jspecify.annotations.Nullable String refusal(final org.fuin.sokar.core.project.Mail mail,
            final String peer) {
        if (mail.peer(peer) != null || !mail.conversations().isEmpty()) {
            return null;
        }
        return "this project has no peer '" + peer + "' and no conversation, so nothing would carry it: name the"
                + " peer in the project file, or give the project a transport under mail.transports";
    }

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
        // Through a dot file and a rename, the same discipline the agent is held to: a message is
        // complete exactly when it is renamed, and nothing here gets a shortcut.
        final Path staged = mailbox.person().resolve("." + name);
        Files.createDirectories(mailbox.person());
        Files.writeString(staged, Json.write(message), StandardCharsets.UTF_8);
        Files.move(staged, mailbox.person().resolve(name), StandardCopyOption.ATOMIC_MOVE);
        return name;
    }
}
