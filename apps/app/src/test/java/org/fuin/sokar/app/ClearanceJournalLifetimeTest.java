package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A clearance journal outlives its task as a record, and is never read by the next task of the name.
 */
class ClearanceJournalLifetimeTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private Path journal(final SokarContext context) throws IOException {
        final Path journal = context.paths().tasks().clearanceJournal("sokar-uc-shell");
        Files.createDirectories(journal.getParent());
        return Files.writeString(journal, "{\"key\":\"tcp/192.0.2.1/443\",\"verdict\":\"allow\"}\n");
    }

    @Test
    void stoppingKeepsTheDecisionsForTheSameTaskToResumeWith(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        final Path journal = journal(context);
        runner.answering("ps", "sokar-uc-shell\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");

        new TaskControl(context).stop("sokar-uc-shell");

        assertThat(journal).exists();
    }

    @Test
    void removingKeepsTheRecordWhereTheNextTaskOfTheNameDoesNotReadIt(@TempDir final Path dir)
            throws IOException {
        // A new task of the same project and name gets the same container name, and read this journal
        // back: an address allowed for the removed task was open in the new one without being asked.
        final SokarContext context = context(dir);
        final Path journal = journal(context);
        runner.answering("ps", "sokar-uc-shell\tExited (143)\t1700000000\t1700000100\tuc\tguarded\n");

        new TaskControl(context).remove("sokar-uc-shell", false, true);

        assertThat(journal).doesNotExist();
        try (var kept = Files.list(journal.getParent())) {
            assertThat(kept.map(path -> path.getFileName().toString()))
                    .singleElement().asString().startsWith("sokar-uc-shell.removed-").endsWith(".jsonl");
        }
    }
}
