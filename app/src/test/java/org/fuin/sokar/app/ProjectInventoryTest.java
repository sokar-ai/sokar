package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProjectInventory}, which answers the question an interface cannot answer for
 * itself: what projects are there, and where is each one's file.
 */
class ProjectInventoryTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private SokarContext context(Path dir) {
        root = dir;
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private void mirror(String name) throws IOException {
        Files.createDirectories(root.resolve("data/sokar/mirrors").resolve(name + ".git"));
    }

    private void task(String container, String project) throws IOException {
        final Path state = root.resolve("run/sokar").resolve(container);
        Files.createDirectories(state);
        new Sidecar(Sidecar.VERSION, project, "guarded", state.resolve("r.nft").toString(),
                state.resolve("dns.conf").toString(), "/usr/bin/sokar", state.toString())
                .writeTo(state.resolve("sidecar.json"));
    }

    private Path registry(Path dir, String json) throws IOException {
        final Path file = dir.resolve("data/sokar/projects.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void findsAProjectByItsMirrorAlone(@TempDir Path dir) throws IOException {

        // The mirror outlives every task, so it is the only durable list of names there is.
        final SokarContext context = context(dir);
        mirror("uc");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.name()).isEqualTo("uc");
                    assertThat(project.mirror()).endsWith("uc.git");
                    assertThat(project.file()).isNull();
                });
    }

    @Test
    void findsAProjectThatHasOnlyATask(@TempDir Path dir) throws IOException {

        // A task that ran without a gate leaves no mirror, and its project still exists.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-nogate-shell-1\tUp 4 minutes\n");
        task("sokar-nogate-shell-1", "nogate");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.name()).isEqualTo("nogate");
                    assertThat(project.securityClass()).isEqualTo("guarded");
                    assertThat(project.tasks()).isEqualTo(1);
                    assertThat(project.mirror()).isNull();
                });
    }

    @Test
    void namesWhereEachProjectFileIs(@TempDir Path dir) throws IOException {

        // The one thing a client on another machine cannot work out, and what every gate method
        // takes.
        final SokarContext context = context(dir);
        mirror("uc");
        final Path projectFile = Files.writeString(dir.resolve("project.yml"), "project:\n");
        registry(dir, "{\"uc\":\"" + projectFile + "\"}");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.file()).isEqualTo(projectFile.toString()));
    }

    @Test
    void reportsAFileThatHasMovedAsAbsent(@TempDir Path dir) throws IOException {

        // Handing back a path nothing can read makes the next gate call fail for a reason that
        // looks like a fault in the daemon.
        final SokarContext context = context(dir);
        mirror("uc");
        registry(dir, "{\"uc\":\"" + dir.resolve("moved-away.yml") + "\"}");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.file()).isNull());
    }

    @Test
    void countsWhatIsWaitingForReview(@TempDir Path dir) throws IOException {

        // Asked of git, not counted as files: a packed mirror keeps its refs in one file, and a
        // loose-file count would report a full queue as empty.
        final SokarContext context = context(dir);
        mirror("uc");
        runner.answering("for-each-ref",
                "refs/sokar/incoming/shell\nrefs/sokar/incoming/build\n");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.pending()).isEqualTo(2));
    }

    @Test
    void keepsAProjectWhoseTaskAndMirrorAreBothGone(@TempDir Path dir) throws IOException {

        // It ran once and everything was removed. The file is still what an interface acts on.
        final SokarContext context = context(dir);
        final Path projectFile = Files.writeString(dir.resolve("project.yml"), "project:\n");
        registry(dir, "{\"ghost\":\"" + projectFile + "\"}");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.name()).isEqualTo("ghost");
                    assertThat(project.tasks()).isZero();
                    assertThat(project.file()).isEqualTo(projectFile.toString());
                });
    }

    @Test
    void countsEveryTaskOfAProjectOnce(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps",
                "sokar-uc-shell-1\tUp 4 minutes\nsokar-uc-build-2\tExited (0) 1 hour ago\n");
        task("sokar-uc-shell-1", "uc");
        task("sokar-uc-build-2", "uc");
        mirror("uc");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.tasks()).isEqualTo(2);
                    assertThat(project.mirror()).endsWith("uc.git");
                });
    }
}
