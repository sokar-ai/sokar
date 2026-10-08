package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.HelperPid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a person is told about an online task's builds when the helper that follows them is not there: a launch that
 * stopped before it, or a helper that ended while the task runs.
 */
class TaskBuildsHelperTest {

    private static final String TASK = "sokar-uc-writer";

    @TempDir
    Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context() throws IOException {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        new FollowedProjects(context.paths().projects().followed()).write(new FollowedProjects.Followed("uc",
                "git@example.com:x/uc.git", "a1b2c3", "2026-10-06T00:00:00Z", "APPLIED", ""));
        final Path clone = context.paths().projects().followedClone("uc");
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("project.yml"), """
                project:
                  name: "uc"
                  security_class: "online"
                  upstream: "git@example.com:x/uc.git"
                image:
                  base_image: "ubuntu:24.04"
                builds:
                  forge: "github"
                  credential: "forge-token"
                """);
        Files.createDirectories(context.paths().tasks().containerState(TASK));
        runner.answering("ps", TASK + "\tUp 3 minutes\t\t\tuc\tonline\n");
        return context;
    }

    private TaskBuilds.View view(final SokarContext context) {
        return new TaskInventory(context).tasks().stream().filter(task -> task.name().equals(TASK)).findFirst()
                .orElseThrow().builds();
    }

    @Test
    void theHelperFollowsTheBranchTheGatePassedThePushOnTo() {
        // An online task pushed to refs/heads/<task> at the forge itself; now its gate passes its own ref on as
        // sokar/<task>, and that is the branch the forge builds.
        assertThat(BuildsWiring.branchAtTheUpstream("refs/sokar/incoming/builds")).isEqualTo("sokar/builds");
        assertThat(BuildsWiring.branchAtTheUpstream("refs/heads/builds")).isNull();
        assertThat(BuildsWiring.branchAtTheUpstream("refs/sokar/incoming/")).isNull();
        assertThat(BuildsWiring.branchAtTheUpstream(null)).isNull();
    }

    @Test
    void aLaunchThatStoppedBeforeTheHelperSaysSoForAProjectThatNamesAForge() throws IOException {

        // The launch hung in the workspace, was ended, and the task ran with nothing said about its
        // builds - neither the reader nor why there was none.
        final TaskBuilds.View view = view(context());

        assertThat(view.reader()).isEqualTo("github");
        assertThat(view.problem()).contains("has not started");
    }

    @Test
    void aHelperThatEndedWhileTheTaskRunsSaysSoAndOneThatRunsSaysNothing() throws Exception {
        final SokarContext context = context();
        new TaskBuilds(context.paths().tasks()).reader(TASK, "github", "");

        assertThat(view(context).problem()).as("no helper's process at all").contains("is not running");

        final Process helper = new ProcessBuilder("sleep", "60").start();
        try {
            HelperPid.record(context.paths().tasks().containerState(TASK).resolve(BuildsWiring.HELPER + ".pid"),
                    helper.toHandle());
            assertThat(view(context).problem()).as("its helper runs").isEmpty();
        } finally {
            helper.destroyForcibly();
        }
    }
}
