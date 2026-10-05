package org.fuin.sokar.clearance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * The record of what a task was allowed and refused, one JSON object per line.
 * <p>
 * <strong>It outlives the task on purpose.</strong> Everything else a task writes is under the
 * runtime directory, which the kernel clears at logout and {@code task stop --remove} deletes
 * outright - and a record of what an agent tried to reach that disappears with the task is not an
 * audit record. This one is written under the state directory instead.
 * <p>
 * It is also what a restarted watcher reads back. A resumed task starts a fresh watcher against a
 * fresh firewall, and without this file every destination already decided would be asked about
 * again - which is both an operator answering the same questions twice and, after a deny, an agent
 * getting a second attempt at an answer it did not like.
 */
public class ClearanceJournal {

    private final Path file;

    /**
     * Constructor with the file to append to. Nothing is created until something is recorded.
     *
     * @param file Where the decisions are written.
     */
    public ClearanceJournal(Path file) {
        this.file = file;
    }

    /**
     * Returns the file being written.
     *
     * @return The journal file.
     */
    public Path file() {
        return file;
    }

    /**
     * Appends one decision.
     *
     * @param decision What was decided.
     * @throws ClearanceException If it cannot be written, because a decision that was not recorded
     *         has to be said out loud rather than assumed.
     */
    public void record(Decision decision) {
        final Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("at", decision.at().toString());
        line.put("project", decision.project());
        line.put("task", decision.task());
        line.put("key", decision.key());
        line.put("destination", decision.destination());
        line.put("port", decision.port());
        line.put("protocol", decision.protocol());
        line.put("shown", decision.shown());
        line.put("verdict", decision.verdict().name().toLowerCase(java.util.Locale.ROOT));
        line.put("source", decision.source());
        try {
            if (!Files.exists(file)) {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                // Made owner-only before its first line, not restricted after it: what a task reached is nobody
                // else's business, and the directory it sits in is shared with every other Sokar state file.
                try {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(
                            "rw-------")));
                } catch (java.nio.file.FileAlreadyExistsException ex) {
                    // Another thread made it first, owner-only as well.
                }
            }
            Files.writeString(file, Json.write(line) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new ClearanceException("Cannot record the decision in " + file + ": "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * Reads back what was decided, oldest first.
     * <p>
     * A file that is not there yet is not an error: it is a task that has not been asked anything.
     *
     * @return The decisions, in the order they were made.
     * @throws ClearanceException If the file exists and cannot be read.
     */
    public List<Decision> read() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<Decision> decisions = new ArrayList<>();
        try {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final Decision decision = parse(line);
                if (decision != null) {
                    decisions.add(decision);
                }
            }
        } catch (IOException ex) {
            throw new ClearanceException("Cannot read " + file + ": " + ex.getMessage(), ex);
        }
        return List.copyOf(decisions);
    }

    /**
     * Turns one line back into a decision.
     *
     * @param line One line of the file.
     * @return The decision, or {@code null} if the line is not one - a process killed mid-write
     *         leaves a partial last line, and refusing to start over it would cost the task every
     *         decision before it.
     */
    private static @Nullable Decision parse(String line) {
        try {
            if (!(Json.parse(line) instanceof Map<?, ?> fields)) {
                return null;
            }
            final String verdict = String.valueOf(fields.get("verdict"));
            return new Decision(
                    Instant.parse(String.valueOf(fields.get("at"))),
                    String.valueOf(fields.get("project")),
                    String.valueOf(fields.get("task")),
                    String.valueOf(fields.get("key")),
                    String.valueOf(fields.get("destination")),
                    fields.get("port") instanceof Number port ? port.intValue() : 0,
                    String.valueOf(fields.get("protocol")),
                    String.valueOf(fields.get("shown")),
                    Verdict.valueOf(verdict.toUpperCase(java.util.Locale.ROOT)),
                    String.valueOf(fields.get("source")));
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
