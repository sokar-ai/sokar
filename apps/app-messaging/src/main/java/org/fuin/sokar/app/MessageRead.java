package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;

/**
 * Shows a person one held message before they decide about it.
 * <p>
 * <strong>Without it, releasing is a formality.</strong> A message held for a person is waiting for
 * somebody to read it, and an interface that reaches the machine through a forwarded socket has no
 * file to read - so a person would release it unread, and {@code prompt} would decide nothing.
 * <p>
 * <strong>Looked up exactly as a release looks it up</strong>, in {@code hold/}, among what the filter
 * refused and what it could not check. A refused one is shown in full at the operator's word, so a
 * person can still decide to deliver it; the list leaves every text out.
 */
public final class MessageRead {

    /** What a read found. */
    public enum Outcome {

        /** One held message, returned. */
        FOUND,

        /** Nothing held is called that. */
        NO_SUCH_MESSAGE,

        /** Several held messages answer to that name, so a person says which. */
        AMBIGUOUS
    }

    /**
     * One held message, as a person decides about it.
     *
     * @param outcome What the read found.
     * @param standing Where it stands: {@code held}, {@code refused-by-filter}, {@code refused-by-person}
     *        or {@code unchecked}, or "" when nothing was found.
     * @param message The file, or "".
     * @param id Its message id, or "".
     * @param role {@code ROLE_USER} when a person wrote it, {@code ROLE_AGENT} when a task did, or "".
     * @param peer The peer it was going to or came from, as the record names it, or "".
     * @param kind What it is - question, answer, status, review-request or handover - or "".
     * @param at When this host first recorded it, RFC 3339, or "" when the record does not name it.
     * @param text Its text parts, in order; a part that is not text is named by its media type.
     * @param reason Why it is held, as the record says, or "".
     * @param direction {@code out} when this task was sending it, {@code in} when it was reaching this task, or
     *        "" when the record is older than the directions it keeps.
     */
    public record Held(Outcome outcome, String standing, String message, String id, String role, String peer, String kind,
            String at, List<String> text, String reason, String direction) {

        /**
         * Constructor with a copy of the parts.
         *
         * @param outcome What the read found.
         * @param standing Where it stands.
         * @param message The file.
         * @param id Its id.
         * @param role Who wrote it.
         * @param peer The peer.
         * @param kind What it is.
         * @param at When it was first recorded.
         * @param text Its parts.
         * @param reason Why it is held.
         * @param direction Which way it was going.
         */
        public Held {
            text = List.copyOf(text);
        }

        static Held none(final Outcome outcome) {
            return new Held(outcome, "", "", "", "", "", "", "", List.of(), "", "");
        }
    }

    /**
     * Reads one held message.
     *
     * @param mailbox The task's mailbox.
     * @param id The message id or the file name, as a release takes it.
     * @return The message, or why there is none to show.
     * @throws IOException Reading failed.
     */
    public Held read(final Mailbox mailbox, final String id) throws IOException {
        final List<MessageLookup.Found> found = MessageLookup.find(mailbox, id);
        if (found.size() != 1) {
            return Held.none(found.isEmpty() ? Outcome.NO_SUCH_MESSAGE : Outcome.AMBIGUOUS);
        }
        return describe(mailbox, found.get(0), new MessageRecord(mailbox).entries(), true);
    }

    /**
     * Lists every message a person may be asked about in one mailbox, without what they say.
     * <p>
     * <strong>So an interface that starts late still knows what waits.</strong> The stream says what
     * happens from the moment somebody listens; this says what is there. Each entry is exactly what
     * {@link #read} would answer for it, text left out: a list that quoted refused messages would be
     * a second copy of what was refused, and reading one is a person's deliberate act.
     *
     * @param mailbox The task's mailbox.
     * @return Every held, refused and unchecked message, held first; {@code text} is empty in each.
     * @throws IOException Reading failed.
     */
    public List<Held> list(final Mailbox mailbox) throws IOException {
        final List<Map<String, Object>> record = new MessageRecord(mailbox).entries();
        final List<Held> listed = new ArrayList<>();
        for (final MessageLookup.Found found : MessageLookup.all(mailbox)) {
            listed.add(describe(mailbox, found, record, false));
        }
        return listed;
    }

    private static Held describe(final Mailbox mailbox, final MessageLookup.Found found,
            final List<Map<String, Object>> record, final boolean withText) throws IOException {
        final Path held = found.file();
        final MessageLookup.Standing standing = found.standing();
        final String name = held.getFileName().toString();
        // What the filter could not check may not be JSON at all; then it is shown as the text it is.
        Object parsed;
        try {
            parsed = Json.parse(lenient(held));
        } catch (final RuntimeException notJson) {
            parsed = Map.of("parts", List.of(Map.of("text", lenient(held))));
        }
        final Map<?, ?> document = parsed instanceof Map<?, ?> map ? map : Map.of();
        final Map<?, ?> metadata = document.get("metadata") instanceof Map<?, ?> map ? map : Map.of();
        final List<String> text = new ArrayList<>();
        if (document.get("parts") instanceof List<?> parts) {
            for (final Object part : parts) {
                if (part instanceof Map<?, ?> each && each.get("text") instanceof String words) {
                    text.add(words);
                } else if (part instanceof Map<?, ?> each) {
                    text.add("[" + (each.get("mediaType") instanceof String type ? type : "a part that is not text") + "]");
                }
            }
        }
        // Why and with whom from the record, which the pass wrote as it held it: the message itself
        // says whom a task meant, the record says what this host made of it.
        String peer = "";
        String reason = "";
        String at = "";
        boolean heldLine = false;
        String direction = "";
        for (final Map<String, Object> line : record) {
            if (!name.equals(line.get("message"))) {
                continue;
            }
            // When: the first line that names it, which is when this host took it in.
            if (at.isEmpty()) {
                at = String.valueOf(line.getOrDefault("at", ""));
            }
            if (MessageRecord.HELD.equals(line.get("event"))) {
                peer = String.valueOf(line.getOrDefault("peer", ""));
                reason = String.valueOf(line.getOrDefault("detail", ""));
                heldLine = true;
                direction = line.get("direction") instanceof String recorded ? recorded : "";
            }
        }
        if (peer.isEmpty() && metadata.get("to") instanceof String to) {
            peer = to;
        }
        final String messageId = text(document.get("messageId"));
        reason = switch (standing) {
            case HELD -> {
                final String verdict = MessageLookup.wouldRefuse(mailbox, messageId);
                yield verdict.isEmpty() ? reason : reason + "; " + verdict;
            }
            case REFUSED_BY_FILTER -> {
                final String answer = MessageLookup.filterAnswer(mailbox, messageId);
                yield answer.isEmpty() ? "the filter refused it; its answer is not on this machine any more" : answer;
            }
            case REFUSED_BY_PERSON -> "a person refused it for good";
            case UNCHECKED -> "the filter could not check it at all";
        };
        // Held with no line of its own is intake's, which holds only this task's own messages; what the filter
        // refused or could not check was on its way out, since the filter checks what a task sends.
        if (standing != MessageLookup.Standing.HELD || !heldLine) {
            direction = MessageRecord.OUT;
        }
        return new Held(Outcome.FOUND, standing.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'), name, messageId, text(document.get("role")), peer,
                text(metadata.get("kind")), at, withText ? text : List.of(), reason, direction);
    }

    /** Reads a file as UTF-8, showing a byte that is not as the replacement character rather than failing. */
    private static String lenient(final Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String text(final @org.jspecify.annotations.Nullable Object value) {
        return value instanceof String string ? string : "";
    }
}
