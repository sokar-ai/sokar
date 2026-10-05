package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task is what Sokar recorded, never what is named like one (the operator, 2026-10-04).
 */
class RecognizedTasksTest {

    @TempDir
    private Path dir;

    @Test
    void everyContextACommandOrTheDaemonBuildsRecognizesTasksByTheirRecord() {
        assertThat(SokarContext.real().recognizesTasks()).isTrue();
    }

    @Test
    void aForeignContainerNamedAndLabelledLikeATaskIsNoTaskAndGetsNothing() throws IOException {
        final FakeCommandRunner runner = new FakeCommandRunner();
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0).recognizingTasks();
        Sidecar.recordId(context.paths().tasks().containerState("sokar-p-real"), "a1b2c3d4e5f6");
        runner.answering("ps", "sokar-p-real\tUp 1 minute\t\t\tp\tguarded\t\t\ta1b2c3d4e5f6\n"
                + "sokar-p-forged\tUp 1 minute\t\t\tp\tguarded\t\t\t0f0f0f0f0f0f\n");

        assertThat(new TaskInventory(context).tasks()).extracting(TaskInventory.Task::name)
                .containsExactly("sokar-p-real");
        assertThat(new AgentWake(context).wake("sokar-p-forged")).isFalse();
        assertThat(context.podman().screen("sokar-p-forged", 5, false).live()).isFalse();
        assertThat(runner.invocations()).noneMatch(command -> command.arguments().contains("sokar-p-forged"));
    }

    @Test
    void aTaskARebootStoppedIsStillRecognizedByItsSavedRecord() throws IOException {

        // A reboot empties the runtime directory, where the id was first written: found by the suite on 2026-10-04,
        // a restarted task was no task any more.
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0).recognizingTasks();
        final Path runtime = context.paths().tasks().containerState("sokar-p-kept");
        Sidecar.recordId(runtime, "a1b2c3d4e5f6");
        new TaskState(context).save("sokar-p-kept");
        java.nio.file.Files.delete(runtime.resolve(Sidecar.RECORDED_ID));

        assertThat(context.recordedId("sokar-p-kept")).isEqualTo("a1b2c3d4e5f6");
    }
}
