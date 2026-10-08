package org.fuin.sokar.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What backups have been taken, and where they were put.
 * <p>
 * <strong>Nothing recorded this before, and nothing could have worked out the answer.</strong>
 * {@code gate backup &lt;file&gt;} writes a bundle wherever the operator names and forgets it, so
 * "what has been taken" was not a question this machine could answer - and an interface cannot
 * offer to restore from a list it cannot read.
 * <p>
 * <strong>A record is not the bundle.</strong> The file it names can be moved, deleted or filled
 * with something else afterwards, and this cannot know. So a listing says whether the file is still
 * there and how big it is now, and never claims the contents are what was written. What was true
 * when it was taken is what the record carries.
 * <p>
 * Append-only, one line per backup, one file per project. A project has many backups over time and
 * the interesting ones are the recent ones, so nothing is rewritten and nothing is lost by a
 * concurrent writer appending at the same moment.
 */
public final class BackupRecords {

    /**
     * One backup, as it was taken.
     *
     * @param taken When it was written.
     * @param bundle Absolute path it was written to.
     * @param refs How many refs the mirror held at the time.
     * @param present Whether that file is there now.
     * @param bytes Its size now, or 0 when it is gone.
     */
    public record Backup(Instant taken, Path bundle, int refs, boolean present, long bytes) { }

    private final Path directory;

    /**
     * Constructor with the directory to keep.
     *
     * @param directory Where one file per project lives.
     */
    public BackupRecords(Path directory) {
        this.directory = directory;
    }

    /**
     * Records a backup that was just taken.
     *
     * @param project Project name.
     * @param bundle Where it was written.
     * @param refs How many refs the mirror held.
     */
    public void add(String project, Path bundle, int refs) {
        try {
            Files.createDirectories(directory);
            // Absolute, because a relative path recorded from one directory means something else
            // read from another - and this is read by a daemon that was started somewhere else.
            final String line = String.join("\t", Instant.now().toString(),
                    bundle.toAbsolutePath().toString(), String.valueOf(refs)) + "\n";
            Files.writeString(directory.resolve(project), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot record the backup of " + project, ex);
        }
    }

    /**
     * Returns what has been taken for one project, newest first.
     *
     * @param project Project name.
     * @return The backups, each saying whether its file is still there.
     */
    public List<Backup> of(String project) {
        final Path file = directory.resolve(project);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        final List<Backup> found = new ArrayList<>();
        try {
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String[] parts = line.strip().split("\t", -1);
                try {
                    final Path bundle = Path.of(parts[1]);
                    final boolean present = Files.isRegularFile(bundle);
                    found.add(new Backup(Instant.parse(parts[0]), bundle,
                            Integer.parseInt(parts[2]), present,
                            present ? Files.size(bundle) : 0L));
                } catch (RuntimeException | IOException malformed) {
                    // A half-written line from a crash, or a path this platform cannot parse.
                    // Skipping it loses one entry; failing the whole listing would hide every
                    // other backup this project has, which is the opposite of what somebody
                    // needs at that moment.
                    //
                    // This is the only guard: a length check in front of it was removed because
                    // the two covered for each other, so neither could be shown to matter.
                    continue;
                }
            }
        } catch (IOException ex) {
            return List.of();
        }
        found.sort(java.util.Comparator.comparing(Backup::taken).reversed());
        return List.copyOf(found);
    }

    /**
     * Forgets one backup, without touching the file it names.
     * <p>
     * Deleting the bundle is a separate act with a separate consequence, and the caller decides
     * it. Forgetting a record for a file somebody already deleted must not fail.
     *
     * @param project Project name.
     * @param bundle Which backup to forget.
     * @return Whether a record was removed.
     */
    public boolean forget(String project, Path bundle) {
        final Path file = directory.resolve(project);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            final String wanted = bundle.toAbsolutePath().toString();
            final List<String> kept = new ArrayList<>();
            boolean removed = false;
            for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                final String[] parts = line.strip().split("\t", -1);
                if (parts.length >= 2 && parts[1].equals(wanted)) {
                    removed = true;
                } else if (!line.isBlank()) {
                    kept.add(line);
                }
            }
            if (removed) {
                Files.writeString(file, kept.isEmpty() ? "" : String.join("\n", kept) + "\n",
                        StandardCharsets.UTF_8);
            }
            return removed;
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot rewrite the backup record of " + project, ex);
        }
    }
}
