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
    public static final String HELD = "held";

    /** It was handed to the agent. */
    public static final String DELIVERED = "delivered";

    /** It arrived again after having been delivered. */
    public static final String DUPLICATE = "duplicate";

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
        final String previous = lastHash();
        final Map<String, Object> line = new LinkedHashMap<>();
        line.put("at", Instant.now().toString());
        line.put("event", event);
        line.put("message", message);
        line.put("id", id);
        line.put("detail", detail);
        line.put("previous", previous);
        line.put("hash", hash(previous, event, message, id, detail));
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
                    String.valueOf(line.get("detail")));
            if (!expected.equals(String.valueOf(line.get("hash")))
                    || !previous.equals(String.valueOf(line.get("previous")))) {
                return number;
            }
            previous = expected;
        }
        return 0;
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
                parsed.add(Map.of("event", "", "message", "", "id", "", "detail", "",
                        "previous", "?", "hash", "?"));
            }
        }
        return parsed;
    }

    private String lastHash() throws IOException {
        final List<Map<String, Object>> lines = lines();
        return lines.isEmpty() ? "" : String.valueOf(lines.get(lines.size() - 1).get("hash"));
    }

    private String hash(final String previous, final String event, final String message,
            final String id, final String detail) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Length-prefixed rather than separated: a separator can appear inside a reason, and
            // two different sets of fields would then hash alike.
            final StringBuilder material = new StringBuilder();
            for (final String field : List.of(previous, event, message, id, detail)) {
                material.append(field.length()).append(':').append(field);
            }
            digest.update(material.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
