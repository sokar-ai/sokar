package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskBuildsTest {

    private static final String TASK = "sokar-demo-shell-1";

    private static final String COMMIT = "c".repeat(40);

    @TempDir
    Path dir;

    private TaskPaths paths;

    private TaskBuilds builds;

    @BeforeEach
    void aTask() throws IOException {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        paths = new SokarPaths(xdg, dir.resolve("bin")).tasks();
        Files.createDirectories(paths.containerState(TASK));
        Sidecar.recordId(paths.containerState(TASK), "0123456789ab");
        builds = new TaskBuilds(paths);
    }

    @Test
    void recordsWhatWasDeliveredAndReadsItBackWithTheLogsSizeAndHashButNotItsText() throws IOException {
        final TaskBuilds.Seen seen = new TaskBuilds.Seen(COMMIT, "failure", List.of(
                new TaskBuilds.Job("Build / unit", "failure", new TaskBuilds.Log("build-cccccccccccc-1.log", 33,
                        "a".repeat(64))),
                new TaskBuilds.Job("Build / docs", "success", null)), "2026-10-06T12:00:00Z", "", "0123456789ab");

        builds.write(TASK, seen);

        assertThat(builds.record(TASK)).containsExactly(seen);
        assertThat(Files.readString(dir.resolve("state/sokar/builds/" + TASK + ".jsonl"), StandardCharsets.UTF_8))
                .as("the record outlives the task, in the state directory")
                .contains("\"bytes\":33").contains("build-cccccccccccc-1.log");
    }

    @Test
    void showsTheCurrentRunsBuildsOnlyEachWithItsNewestVerdictTheNewestPushFirst() throws IOException {
        builds.write(TASK, seen("1".repeat(40), "success", "ba9876543210"));
        builds.write(TASK, seen("2".repeat(40), "running", "0123456789ab"));
        builds.write(TASK, seen("3".repeat(40), "queued", "0123456789ab"));
        builds.write(TASK, seen("2".repeat(40), "failure", "0123456789ab"));

        assertThat(builds.current(TASK)).extracting(TaskBuilds.Seen::commit, TaskBuilds.Seen::verdict)
                .as("another run of the same name is not this one's; a push keeps its place when its verdict changes")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("3".repeat(40), "queued"),
                        org.assertj.core.groups.Tuple.tuple("2".repeat(40), "failure"));
    }

    @Test
    void saysWhichReaderFollowsTheBuildsAndWhatKeepsItFromDoingSo() throws IOException {
        assertThat(builds.view(TASK)).as("nothing follows them").isEqualTo(TaskBuilds.View.none());

        builds.reader(TASK, "github", "");
        assertThat(builds.view(TASK)).isEqualTo(new TaskBuilds.View("github", "", List.of()));

        builds.reader(TASK, "github", "no build reader for 'github' is installed");
        assertThat(builds.view(TASK).problem()).contains("is installed");
    }

    private static TaskBuilds.Seen seen(final String commit, final String verdict, final String run) {
        return new TaskBuilds.Seen(commit, verdict, List.of(), "2026-10-06T12:00:00Z", "", run);
    }
}
