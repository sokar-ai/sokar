package org.fuin.sokar.clearance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ClearanceJournal}, which is the only thing that outlives the task it records.
 */
class ClearanceJournalTest {

    private static Decision decision(String destination, Verdict verdict, String source) {
        return new Decision(Instant.parse("2026-09-07T10:15:30Z"), "uc", "shell",
                "tcp/" + destination + "/443", destination, 443, "tcp", destination + ":443",
                verdict, source);
    }

    @Test
    void writesOneLinePerDecision(@TempDir Path dir) throws IOException {

        final ClearanceJournal journal = new ClearanceJournal(dir.resolve("deep/uc-shell.jsonl"));

        journal.record(decision("1.1.1.1", Verdict.ALLOW, Decision.PROMPT));
        journal.record(decision("9.9.9.9", Verdict.DENY, Decision.PROMPT));

        // The directory is created on the way, because the state directory holds nothing for a
        // task until this file.
        assertThat(Files.readAllLines(journal.file())).hasSize(2);
        assertThat(Files.readString(journal.file(), StandardCharsets.UTF_8))
                .contains("\"verdict\":\"allow\"")
                .contains("\"destination\":\"9.9.9.9\"")
                .contains("\"at\":\"2026-09-07T10:15:30Z\"");
    }

    @Test
    void recordsAQuestionNobodyAnswered(@TempDir Path dir) {

        // The whole point of writing it down: the task went on running with that destination
        // blocked, and nothing else says so.
        final ClearanceJournal journal = new ClearanceJournal(dir.resolve("uc-shell.jsonl"));
        journal.record(decision("1.1.1.1", Verdict.TIMEOUT, Decision.PROMPT));

        assertThat(journal.read()).singleElement()
                .satisfies(read -> assertThat(read.verdict()).isEqualTo(Verdict.TIMEOUT));
    }

    @Test
    void readsBackEverythingItWrote(@TempDir Path dir) {

        final ClearanceJournal journal = new ClearanceJournal(dir.resolve("uc-shell.jsonl"));
        final Decision written = decision("1.1.1.1", Verdict.ALLOW, Decision.CLIENT);
        journal.record(written);

        assertThat(journal.read()).containsExactly(written);
    }

    @Test
    void readsNothingFromATaskThatWasNeverAsked(@TempDir Path dir) {

        // Not an error: most tasks reach only what their project declared.
        assertThat(new ClearanceJournal(dir.resolve("uc-shell.jsonl")).read()).isEmpty();
    }

    @Test
    void keepsTheDecisionsBeforeALineThatWasCutShort(@TempDir Path dir) throws IOException {

        // A watcher killed mid-write leaves a partial last line. Refusing to read the file would
        // cost the task every decision made before it, which is the opposite of what the record
        // is for.
        final Path file = dir.resolve("uc-shell.jsonl");
        final ClearanceJournal journal = new ClearanceJournal(file);
        journal.record(decision("1.1.1.1", Verdict.ALLOW, Decision.PROMPT));
        Files.writeString(file, "{\"at\":\"2026-09-07T10:1", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(journal.read()).hasSize(1);
    }

    @Test
    void isReadableByNobodyElse(@TempDir Path dir) throws IOException {

        final ClearanceJournal journal = new ClearanceJournal(dir.resolve("uc-shell.jsonl"));
        journal.record(decision("1.1.1.1", Verdict.ALLOW, Decision.PROMPT));

        // What an agent tried to reach is nobody else's business, and the state directory is
        // shared with every other file Sokar keeps.
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(journal.file())))
                .isEqualTo("rw-------");
    }

    @Test
    void saysSoWhenADecisionCannotBeWritten(@TempDir Path dir) throws IOException {

        // Silence here would be a hole in the record that the record itself cannot show.
        final Path file = dir.resolve("taken");
        Files.createDirectory(file);

        assertThatThrownBy(() -> new ClearanceJournal(file)
                .record(decision("1.1.1.1", Verdict.ALLOW, Decision.PROMPT)))
                .isInstanceOf(ClearanceException.class)
                .hasMessageContaining(file.toString());
    }

    @Test
    void survivesTheTaskItRecords(@TempDir Path dir) throws IOException {

        // The measurement this file exists for: everything else a task writes is under the
        // runtime directory, which 'task stop --remove' deletes outright.
        final Path runtime = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(runtime);
        final ClearanceJournal journal =
                new ClearanceJournal(dir.resolve("state/sokar/clearance/sokar-uc-shell-1.jsonl"));
        journal.record(decision("1.1.1.1", Verdict.DENY, Decision.PROMPT));

        try (var paths = Files.walk(runtime)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }

        assertThat(journal.read()).hasSize(1);
    }

    @Test
    void keepsTheOrderDecisionsWereMadeIn(@TempDir Path dir) {

        // The hub takes the last answer about a destination, so the file has to be read the way
        // it was written.
        final ClearanceJournal journal = new ClearanceJournal(dir.resolve("uc-shell.jsonl"));
        journal.record(decision("1.1.1.1", Verdict.TIMEOUT, Decision.PROMPT));
        journal.record(decision("1.1.1.1", Verdict.ALLOW, Decision.CLIENT));

        assertThat(journal.read()).extracting(Decision::verdict)
                .isEqualTo(List.of(Verdict.TIMEOUT, Verdict.ALLOW));
    }
}
