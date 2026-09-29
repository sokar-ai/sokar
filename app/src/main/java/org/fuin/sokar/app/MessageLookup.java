package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.wire.Json;

/**
 * Finds a message a person is asked to decide about, by id or file name, and says where it stands.
 * <p>
 * <strong>One lookup for reading and deciding</strong>, so what a person read is what they decide
 * about: the same message, named the same way, with no window in which the two could disagree.
 * <p>
 * Three places, and no others: {@code hold/}, what the filter refused ({@code rejected/}) and what it
 * could not check at all ({@code error/}). A refused original holds what was refused in clear text,
 * secrets included; the operator decided that a person may read it in full and still deliver it, so
 * it is found here - by a person asking, never by the stream or the record, which stay without content.
 */
final class MessageLookup {

    /** Where a message stands. */
    enum Standing {

        /** Held for a person: the filter passed it, something on this host stopped it. */
        HELD,

        /** Refused by the filter, and deliverable after all by a person who read it. */
        REFUSED_BY_FILTER,

        /** Refused for good by a person. Final: the sender was told. */
        REFUSED_BY_PERSON,

        /** The filter could not check it at all. Readable, never deliverable. */
        UNCHECKED
    }

    /**
     * One message found.
     *
     * @param file The message.
     * @param standing Where it stands.
     */
    record Found(Path file, Standing standing) {
    }

    private MessageLookup() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Finds every message that answers to an id or a file name.
     *
     * @param mailbox The task's mailbox.
     * @param id The message id or the file name.
     * @return What answers to it, in any of the three places.
     * @throws IOException Reading failed.
     */
    static List<Found> find(final Mailbox mailbox, final String id) throws IOException {
        final List<Found> found = new ArrayList<>();
        for (final Found each : all(mailbox)) {
            if (answers(each.file(), id)) {
                found.add(each);
            }
        }
        return found;
    }

    /**
     * Returns every message a person may be asked about, in the three places, in that order.
     * <p>
     * The one enumeration a list, a read and a decision share: what a list shows is exactly what
     * {@link #find} can find, standing included.
     *
     * @param mailbox The task's mailbox.
     * @return Every message, held first, then refused, then unchecked; each by file name.
     * @throws IOException Reading failed.
     */
    static List<Found> all(final Mailbox mailbox) throws IOException {
        final List<Found> found = new ArrayList<>();
        for (final Path held : messages(mailbox.hold())) {
            found.add(new Found(held, Standing.HELD));
        }
        final java.util.Set<String> refusedByPerson = refusedByPerson(mailbox);
        for (final Path refused : messages(mailbox.rejected())) {
            found.add(new Found(refused, refusedByPerson.contains(refused.getFileName().toString())
                    ? Standing.REFUSED_BY_PERSON : Standing.REFUSED_BY_FILTER));
        }
        for (final Path unchecked : messages(mailbox.error())) {
            found.add(new Found(unchecked, Standing.UNCHECKED));
        }
        return found;
    }

    /**
     * Returns the filter's own answer to a message it refused - why, in its words, without the text.
     *
     * @param mailbox The task's mailbox.
     * @param id The refused message's id.
     * @return The answer's text, or "" when it is not on this machine any more.
     * @throws IOException Reading failed.
     */
    static String filterAnswer(final Mailbox mailbox, final String id) throws IOException {
        if (id.isEmpty()) {
            return "";
        }
        // Wherever the answer is now: waiting in feedback/, or already handed to the agent's inbox.
        for (final Path directory : List.of(mailbox.feedback(), mailbox.inboxNew(), mailbox.inboxCur())) {
            for (final Path answer : messages(directory)) {
                final String text = answerText(answer, id);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    private static String answerText(final Path answer, final String id) {
        try {
            if (!(Json.parse(Files.readString(answer, StandardCharsets.UTF_8)) instanceof Map<?, ?> document)
                    || !(document.get("parts") instanceof List<?> parts)) {
                return "";
            }
            String text = "";
            boolean about = false;
            for (final Object part : parts) {
                if (part instanceof Map<?, ?> each && each.get("text") instanceof String words && text.isEmpty()) {
                    text = words;
                }
                if (part instanceof Map<?, ?> each && each.get("data") instanceof Map<?, ?> data
                        && id.equals(data.get("rejectedMessageId"))
                        && !(data.get("tool") instanceof Map<?, ?> tool && HostBounce.TOOL.equals(tool.get("name")))) {
                    about = true;
                }
            }
            return about ? text : "";
        } catch (final IOException | RuntimeException ex) {
            return "";
        }
    }

    private static java.util.Set<String> refusedByPerson(final Mailbox mailbox) throws IOException {
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (final Map<String, Object> line : new MessageRecord(mailbox).entries()) {
            if (MessageRecord.REFUSED.equals(line.get("event"))) {
                names.add(String.valueOf(line.get("message")));
            }
        }
        return names;
    }

    private static boolean answers(final Path message, final String id) throws IOException {
        return message.getFileName().toString().equals(id) || id.equals(idOf(message));
    }

    private static String idOf(final Path message) {
        try {
            return MessageFile.id(message);
        } catch (final IOException | RuntimeException ex) {
            // What the filter could not check may not parse at all; it still answers to its file name.
            return "";
        }
    }

    private static List<Path> messages(final Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
    }
}
