package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.sokar.wire.Json;

/**
 * What happened to every message, as a log whose lines are chained.
 * <p>
 * Each line carries the hash of the one before it, so a line edited or removed after the fact stops
 * the chain verifying. That is what a transport cannot give us on every route: a branch in a shared
 * repository proves order and immutability to everybody who fetches it, and a log on a laptop proves
 * it to whoever owns the laptop. The second is worth having and is not the first, which is why the
 * head of the chain can be published later rather than trusted harder now.
 * <p>
 * <strong>It holds what happened, never what was said.</strong> Ids, peers, events and reasons - no
 * message text, because a record of refusals that quoted them would be a second copy of exactly what
 * the filter refused to let out.
 */
public final class MessageRecord {

    /** The file, inside the mailbox's record directory. */
    public static final String FILE = "log.jsonl";

    /** A message was taken from the outbox and signed. */
    public static final String TAKEN = "taken";

    /** A message was queued for a transport. */
    public static final String QUEUED = "queued";

    /** A transport took it. */
    public static final String SENT = "sent";

    /** A transport could not take it yet. */
    public static final String DEFERRED = "deferred";

    /** It reached no agent, with a reason. */
    /** A message this task was sending: {@code direction} of a line. */
    public static final String OUT = "out";

    /** A message that was reaching this task: {@code direction} of a line. */
    public static final String IN = "in";

    public static final String HELD = "held";

    /** It was handed to the agent. */
    public static final String DELIVERED = "delivered";

    /** It arrived again after having been delivered. */
    public static final String DUPLICATE = "duplicate";

    /** A person refused it for good. Recorded so a refusal by a person stays final. */
    public static final String REFUSED = "refused";

    /** A person delivered it after reading it, although the filter had refused it. */
    public static final String OVERRIDDEN = "overridden";

    /** What a task sent was read where it went, as the transport's {@code receipt} says. */
    public static final String READ = "read";

    private final Path file;

    /**
     * Constructor.
     *
     * @param mailbox The mailbox whose record this is.
     */
    public MessageRecord(final Mailbox mailbox) {
        this.file = mailbox.record().resolve(FILE);
    }

    /**
     * Appends one line.
     *
     * @param event One of the constants here.
     * @param message The message's file name.
     * @param id Its message id, or "" when nothing could be read.
     * @param detail Why, for the events that have a why. "" otherwise.
     * @throws IOException Writing failed.
     */
    public void append(final String event, final String message, final String id,
            final String detail) throws IOException {
        append(event, message, id, "", detail);
    }

    /**
     * Appends one line that names the peer it is about.
     *
     * @param event One of the constants here.
     * @param message The message's file name.
     * @param id Its message id, or "" when nothing could be read.
     * @param peer The peer it was going to or came from, or "" when that is not known yet.
     * @param detail Why, for the events that have a why. "" otherwise.
     * @throws IOException Writing failed.
     */
    public void append(final String event, final String message, final String id,
            final String peer, final String detail) throws IOException {
        append(event, message, id, peer, detail, "");
    }

    /**
     * Appends one line that also says which way the message was going.
     *
     * @param event One of the constants here.
     * @param message The message's file name.
     * @param id Its message id, or "" when nothing could be read.
     * @param peer The peer it was going to or came from, or "".
     * @param detail Why, for the events that have a why. "" otherwise.
     * @param direction {@link #OUT} or {@link #IN}, or "" when that is not this line's to say.
     * @throws IOException Writing failed.
     */
    public void append(final String event, final String message, final String id,
            final String peer, final String detail, final String direction) throws IOException {
        final String previous = lastHash();
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", Instant.now().toString());
        line.put("event", event);
        line.put("message", message);
        line.put("id", id);
        line.put("peer", peer);
        line.put("detail", detail);
        if (!direction.isEmpty()) {
            line.put("direction", direction);
        }
        line.put("previous", previous);
        line.put("hash", hash(previous, event, message, id, peer, detail, direction));
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(line) + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Returns the ids of messages already handed to this agent.
     * <p>
     * Read from the record rather than kept beside it: a second list is a second thing to keep in
     * step, and this one is written by the act it describes.
     *
     * @return The ids, in the order they were delivered.
     * @throws IOException Reading failed.
     */
    public Set<String> delivered() throws IOException {
        final Set<String> ids = new LinkedHashSet<>();
        for (final Map<String, Object> line : lines()) {
            if (DELIVERED.equals(line.get("event"))
                    && !String.valueOf(line.get("id")).isBlank()) {
                ids.add(String.valueOf(line.get("id")));
            }
        }
        return ids;
    }

    /**
     * Counts what happened with one peer since a moment.
     *
     * @param event The event to count, one of the constants here.
     * @param peer The peer.
     * @param since Only lines written at or after this.
     * @return How many there were.
     * @throws IOException Reading failed.
     */
    public int count(final String event, final String peer, final Instant since)
            throws IOException {
        int found = 0;
        for (final Map<String, Object> line : lines()) {
            if (event.equals(line.get("event")) && peer.equals(line.get("peer"))
                    && at(line).isAfter(since)) {
                found++;
            }
        }
        return found;
    }

    private Instant at(final Map<String, Object> line) {
        try {
            return Instant.parse(String.valueOf(line.get("at")));
        } catch (final RuntimeException ex) {
            // A line whose time cannot be read counts as old rather than as now: a budget that a
            // damaged line can exhaust would be a way to silence a task by corrupting its record.
            return Instant.EPOCH;
        }
    }

    /**
     * Checks the chain.
     *
     * @return The number of the first line that does not verify, or zero when the whole record
     *         holds together. An absent record holds together: nothing has happened yet.
     * @throws IOException Reading failed.
     */
    public int firstBrokenLine() throws IOException {
        String previous = "";
        int number = 0;
        for (final Map<String, Object> line : lines()) {
            number++;
            final String expected = hash(previous, String.valueOf(line.get("event")),
                    String.valueOf(line.get("message")), String.valueOf(line.get("id")),
                    String.valueOf(line.get("peer")), String.valueOf(line.get("detail")),
                    line.get("direction") instanceof String direction ? direction : "");
            if (!expected.equals(String.valueOf(line.get("hash")))
                    || !previous.equals(String.valueOf(line.get("previous")))) {
                return number;
            }
            previous = expected;
        }
        return 0;
    }

    /**
     * Returns every line, in the order they were written.
     * <p>
     * For something that shows a conversation rather than checks it: the record is the only place
     * that says what happened to a message, so following it is following the conversation.
     *
     * @return The lines, each as it was written.
     * @throws IOException Reading failed.
     */
    public List<Map<String, Object>> entries() throws IOException {
        return List.copyOf(lines());
    }

    /**
     * Returns how many lines the record holds.
     *
     * @return The count.
     * @throws IOException Reading failed.
     */
    public int size() throws IOException {
        return lines().size();
    }

    private List<Map<String, Object>> lines() throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<Map<String, Object>> parsed = new ArrayList<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            if (Json.parse(line) instanceof Map<?, ?> map) {
                final Map<String, Object> entry = new LinkedHashMap<>();
                map.forEach((key, value) -> entry.put(String.valueOf(key), value));
                parsed.add(entry);
            } else {
                // A line that is not an object breaks the chain by being unreadable, and saying so
                // is the point of the record.
                parsed.add(Map.of("event", "", "message", "", "id", "", "peer", "",
                        "detail", "", "previous", "?", "hash", "?"));
            }
        }
        return parsed;
    }

    private String lastHash() throws IOException {
        final List<Map<String, Object>> lines = lines();
        return lines.isEmpty() ? "" : String.valueOf(lines.get(lines.size() - 1).get("hash"));
    }

    private String hash(final String previous, final String event, final String message,
            final String id, final String peer, final String detail, final String direction) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Length-prefixed rather than separated: a separator can appear inside a reason, and
            // two different sets of fields would then hash alike.
            final StringBuilder material = new StringBuilder();
            for (final String field : List.of(previous, event, message, id, peer, detail)) {
                material.append(field.length()).append(':').append(field);
            }
            // Only when there is one: a line written before directions were recorded keeps the hash it
            // was written with, so the chain of an existing record still verifies.
            if (!direction.isEmpty()) {
                material.append(direction.length()).append(':').append(direction);
            }
            digest.update(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
