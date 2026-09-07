package org.fuin.sokar.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.fuin.sokar.gate.UpstreamDistance;
import org.jspecify.annotations.Nullable;

/**
 * What was last measured about each project's distance from its upstream.
 * <p>
 * Measuring reaches the network, so nothing does it on the way to answering a question. This is
 * where the answer waits in between: written by whatever measured, read by anything reporting, and
 * always carrying the time it was taken so an interface can say how much it is worth.
 * <p>
 * <strong>One file per project, like the project registry.</strong> A single shared file was
 * measured losing entries under concurrent writers, and this is written from a timer while it is
 * read by every listing - so the same shape is used for the same reason. A unique temporary name
 * per write, because two writers sharing one would trade halves of each other's content.
 */
final class UpstreamRecords {

    private final Path directory;

    /**
     * Constructor with the directory to keep.
     *
     * @param directory Where one file per project lives.
     */
    UpstreamRecords(Path directory) {
        this.directory = directory;
    }

    /**
     * Records what was measured for one project.
     *
     * @param project Project name.
     * @param distance What was measured.
     */
    void put(String project, UpstreamDistance.Distance distance) {
        try {
            Files.createDirectories(directory);
            final Path temporary = directory.resolve("." + project + "."
                    + ProcessHandle.current().pid() + "." + Thread.currentThread().threadId());
            Files.writeString(temporary, line(distance), StandardCharsets.UTF_8);
            Files.move(temporary, directory.resolve(project),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            // A measurement nobody can store is not a reason to stop measuring the others, and it
            // is certainly not a reason to fail whatever asked.
            throw new UncheckedIOException("Cannot record the upstream distance of " + project, ex);
        }
    }

    /**
     * Returns what was last measured for a project.
     *
     * @param project Project name.
     * @return The distance, or a never-checked answer when nothing has been recorded or the record
     *         cannot be read. An unreadable record reads as never checked on purpose: it is the
     *         one answer that is true whatever the file said.
     */
    UpstreamDistance.Distance get(String project) {
        final Path file = directory.resolve(project);
        if (!Files.isRegularFile(file)) {
            return UpstreamDistance.Distance.neverChecked();
        }
        try {
            return parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException ex) {
            return UpstreamDistance.Distance.neverChecked();
        }
    }

    private static String line(UpstreamDistance.Distance distance) {
        return String.join("\t", distance.reason().name(), String.valueOf(distance.behind()),
                distance.measured() == null ? "" : distance.measured().toString(),
                distance.detail() == null ? "" : distance.detail().replace('\t', ' '))
                + "\n";
    }

    private static UpstreamDistance.Distance parse(String text) {
        final String[] parts = text.strip().split("\t", -1);
        final UpstreamDistance.Reason reason = UpstreamDistance.Reason.valueOf(parts[0]);
        return new UpstreamDistance.Distance(
                parts.length > 1 ? Integer.parseInt(parts[1]) : 0,
                instant(parts.length > 2 ? parts[2] : ""),
                reason,
                parts.length > 3 && !parts[3].isEmpty() ? parts[3] : null);
    }

    @Nullable
    private static Instant instant(String text) {
        return text.isEmpty() ? null : Instant.parse(text);
    }
}
