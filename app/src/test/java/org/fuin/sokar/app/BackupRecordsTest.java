package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for remembering what was backed up and where.
 * <p>
 * The thing this must never do is claim a bundle is there when it is not. A record is written when
 * a backup is taken and the file it names can be moved or deleted afterwards without anything
 * here noticing, so every answer is checked against the disk rather than believed.
 */
class BackupRecordsTest {

    private Path bundle(Path dir, String name) throws Exception {
        final Path file = dir.resolve(name);
        Files.writeString(file, "not really a bundle");
        return file;
    }

    @Test
    void aProjectNobodyHasBackedUpHasNoBackups(@TempDir Path dir) {

        // An ordinary answer, not a fault: it is also what a project backed up by an older Sokar
        // looks like, and neither is worth an error.
        assertThat(new BackupRecords(dir.resolve("records")).of("demo")).isEmpty();
    }

    @Test
    void aBackupIsRememberedWithWhereItWentAndWhatItHeld(@TempDir Path dir) throws Exception {

        final BackupRecords records = new BackupRecords(dir.resolve("records"));
        final Path file = bundle(dir, "one.bundle");

        records.add("demo", file, 3);

        assertThat(records.of("demo")).singleElement().satisfies(backup -> {
            assertThat(backup.bundle()).isEqualTo(file.toAbsolutePath());
            assertThat(backup.refs()).isEqualTo(3);
            assertThat(backup.present()).isTrue();
            assertThat(backup.bytes()).isEqualTo(Files.size(file));
        });
    }

    @Test
    void aBundleSomebodyMovedIsShownAsGoneRatherThanDropped(@TempDir Path dir) throws Exception {

        // Dropping it would report the backup as never taken, which is a different and worse
        // statement: somebody moved a file, and that is exactly what they need to see.
        final BackupRecords records = new BackupRecords(dir.resolve("records"));
        final Path file = bundle(dir, "gone.bundle");
        records.add("demo", file, 1);
        Files.delete(file);

        assertThat(records.of("demo")).singleElement().satisfies(backup -> {
            assertThat(backup.present()).isFalse();
            assertThat(backup.bytes()).isZero();
            assertThat(backup.bundle()).isEqualTo(file.toAbsolutePath());
        });
    }

    @Test
    void theNewestBackupComesFirst(@TempDir Path dir) throws Exception {

        // A list of backups is read to pick one to restore from, and the recent one is almost
        // always the wanted one.
        final BackupRecords records = new BackupRecords(dir.resolve("records"));
        records.add("demo", bundle(dir, "older.bundle"), 1);
        Thread.sleep(5);
        records.add("demo", bundle(dir, "newer.bundle"), 2);

        assertThat(records.of("demo")).extracting(backup -> backup.bundle().getFileName()
                .toString()).containsExactly("newer.bundle", "older.bundle");
    }

    @Test
    void twoProjectsDoNotSeeEachOthersBackups(@TempDir Path dir) throws Exception {

        final BackupRecords records = new BackupRecords(dir.resolve("records"));
        records.add("demo", bundle(dir, "demo.bundle"), 1);
        records.add("other", bundle(dir, "other.bundle"), 1);

        assertThat(records.of("demo")).singleElement()
                .satisfies(backup -> assertThat(backup.bundle().getFileName())
                        .hasToString("demo.bundle"));
    }

    @Test
    void aHalfWrittenLineLosesOneEntryAndNotTheRest(@TempDir Path dir) throws Exception {

        // A crash between opening and writing. Failing the whole listing would hide every other
        // backup this project has, which is the opposite of what somebody needs at that moment.
        final BackupRecords records = new BackupRecords(dir.resolve("records"));
        records.add("demo", bundle(dir, "good.bundle"), 1);
        Files.writeString(dir.resolve("records").resolve("demo"), "truncated-line\n",
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(records.of("demo")).hasSize(1);
    }

    @Test
    void aPathIsRecordedAbsolutelyWhereverItWasTypedFrom(@TempDir Path dir) throws Exception {

        // 'sokar gate backup out.bundle' is run from a project directory; the record is read by a
        // daemon started somewhere else. A relative path stored as given would name a different
        // file to whoever reads it - or no file, which reads as a backup somebody deleted.
        final BackupRecords records = new BackupRecords(dir.resolve("records"));

        records.add("demo", Path.of("relative.bundle"), 1);

        assertThat(records.of("demo")).singleElement().satisfies(backup ->
                assertThat(backup.bundle()).isAbsolute());
    }

    @Test
    void forgettingARecordLeavesTheBundleAlone(@TempDir Path dir) throws Exception {

        // Deleting the file is a separate act with a separate consequence, and it is not this
        // one's to take.
        final BackupRecords records = new BackupRecords(dir.resolve("records"));
        final Path file = bundle(dir, "one.bundle");
        records.add("demo", file, 1);

        assertThat(records.forget("demo", file)).isTrue();

        assertThat(records.of("demo")).isEmpty();
        assertThat(file).exists();
    }

    @Test
    void forgettingSomethingUnknownIsNotAFailure(@TempDir Path dir) throws Exception {

        final BackupRecords records = new BackupRecords(dir.resolve("records"));
        records.add("demo", bundle(dir, "one.bundle"), 1);

        assertThat(records.forget("demo", dir.resolve("never-recorded.bundle"))).isFalse();
        assertThat(records.of("demo")).hasSize(1);
    }

    @Test
    void forgettingOneLeavesTheOthers(@TempDir Path dir) throws Exception {

        final BackupRecords records = new BackupRecords(dir.resolve("records"));
        final Path first = bundle(dir, "first.bundle");
        records.add("demo", first, 1);
        records.add("demo", bundle(dir, "second.bundle"), 2);

        records.forget("demo", first);

        assertThat(records.of("demo")).singleElement()
                .satisfies(backup -> assertThat(backup.bundle().getFileName())
                        .hasToString("second.bundle"));
    }
}
