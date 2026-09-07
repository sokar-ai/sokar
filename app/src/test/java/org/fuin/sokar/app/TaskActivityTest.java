package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Sidecar;
import org.fuin.sokar.wire.TaskMode;
import org.fuin.sokar.wire.TaskProfile;
import org.fuin.sokar.wire.Waiting;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for what a task says about itself beyond its container's state.
 * <p>
 * The distinction being checked is the one that costs an afternoon: a task blocked on a question
 * nobody saw and a task grinding through a build are both {@code running: true}.
 */
class TaskActivityTest {

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

    /** A task the runtime reports, with a state directory and a sidecar. */
    private Path task(String container, String status, String started, String exited)
            throws IOException {
        runner.answering("ps", container + "\t" + status + "\t" + started + "\t" + exited + "\n");
        final Path state = root.resolve("run/sokar").resolve(container);
        Files.createDirectories(state);
        new Sidecar(Sidecar.VERSION, "uc", "guarded", state.resolve("r.nft").toString(),
                state.resolve("dns.conf").toString(), "/usr/bin/sokar", state.toString())
                .writeTo(state.resolve("sidecar.json"));
        return state;
    }

    private TaskInventory.Task only(SokarContext context) {
        return new TaskInventory(context).tasks().getFirst();
    }

    @Test
    void saysWhichAgentAndModeAndBranchItRunsWith(@TempDir Path dir) throws IOException {

        // None of this can be recovered from a running container: two tasks in the same project
        // under the same class were indistinguishable.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        new TaskProfile(TaskProfile.VERSION, "example", TaskMode.UNATTENDED, "fix the parser",
                "refs/sokar/incoming/shell", "2026-09-07T10:00:00Z", "prompt").writeTo(state);

        assertThat(only(context)).satisfies(task -> {
            assertThat(task.agent()).isEqualTo("example");
            assertThat(task.mode()).isEqualTo("unattended");
            assertThat(task.prompt()).isEqualTo("fix the parser");
            assertThat(task.branch()).isEqualTo("refs/sokar/incoming/shell");
            assertThat(task.clearance()).isEqualTo("prompt");
        });
    }

    @Test
    void saysWhenNothingIsEnforcingItsEgress(@TempDir Path dir) throws IOException {

        // The most consequential state a task can be in - nothing asks and nothing is refused -
        // and until it was recorded nothing an interface listed could mark it.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        new TaskProfile(TaskProfile.VERSION, "example", TaskMode.UNATTENDED, "go",
                null, "2026-09-07T10:00:00Z", "off").writeTo(state);

        assertThat(only(context).clearance()).isEqualTo("off");
    }

    @Test
    void saysWhenItsStateBeganAsATimestamp(@TempDir Path dir) throws IOException {

        // "Up 4 minutes" is for a person and must not be parsed, which leaves "idle for forty
        // minutes" answerable only by arithmetic on this.
        final SokarContext context = context(dir);
        task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");

        assertThat(only(context).since()).isEqualTo(Instant.ofEpochSecond(1788500000).toString());
    }

    @Test
    void aStoppedTaskSaysWhenItStoppedRatherThanWhenItStarted(@TempDir Path dir)
            throws IOException {

        // The state that matters is the current one.
        final SokarContext context = context(dir);
        task("sokar-uc-shell-1", "Exited (0) 3 minutes ago", "1788500000", "1788509999");

        assertThat(only(context).since()).isEqualTo(Instant.ofEpochSecond(1788509999).toString());
    }

    @Test
    void saysNothingRatherThanTheYearOneForAContainerThatNeverStarted(@TempDir Path dir)
            throws IOException {

        // Measured on a real machine: a container created and never started answers
        // -62135596800, Go's zero time, which podman renders as "Exited (0) 292 years ago".
        final SokarContext context = context(dir);
        task("sokar-uc-shell-1", "Exited (0) 292 years ago", "1788460208", "-62135596800");

        assertThat(only(context).since()).isEmpty();
    }

    @Test
    void aTaskThatIsAskedSomethingSaysItIsWaiting(@TempDir Path dir) throws IOException {

        // Said by the thing that asked, not inferred from how long it has been quiet.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        new Waiting(state).asking("api.example.test:443");

        assertThat(only(context)).satisfies(task -> {
            assertThat(task.activity()).isEqualTo(TaskInventory.Activity.WAITING);
            assertThat(task.waitingFor()).isEqualTo("api.example.test:443");
        });
    }

    @Test
    void stopsSayingItIsWaitingOnceTheQuestionIsAnswered(@TempDir Path dir) throws IOException {

        // A marker left behind would make a working task look stuck, which is the same mistake in
        // the other direction.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        final Waiting waiting = new Waiting(state);
        waiting.asking("api.example.test:443");
        waiting.answered();

        assertThat(only(context).activity()).isNotEqualTo(TaskInventory.Activity.WAITING);
        assertThat(only(context).waitingFor()).isNull();
    }

    @Test
    void aTaskProducingOutputIsWorking(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        Files.writeString(state.resolve("task.log"), "the agent said something\n");

        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.WORKING);
    }

    @Test
    void aTaskWhoseOutputHasStoppedIsIdleAndNotWaiting(@TempDir Path dir) throws IOException {

        // Quiet is not waiting. An interface that showed this as "waiting for you" would send
        // somebody to answer a question nobody asked.
        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");
        final Path log = Files.writeString(state.resolve("task.log"), "quiet for a while\n");
        Files.setLastModifiedTime(log, FileTime.from(Instant.now().minusSeconds(3600)));

        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.IDLE);
    }

    @Test
    void aTaskNothingCanSeeIntoSaysSoRatherThanGuessing(@TempDir Path dir) throws IOException {

        // An attached session writes to the operator's terminal, not to a file this can read. A
        // state that is silently wrong is worse than one that admits it does not know.
        final SokarContext context = context(dir);
        task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");

        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.UNKNOWN);
    }

    @Test
    void aTaskWhoseContainerIsGoneIsDeadRatherThanIdle(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        final Path state = task("sokar-uc-shell-1", "Exited (137) 1 hour ago", "1788500000",
                "1788509999");
        // Even with a marker left behind by whatever was asking when it died.
        new Waiting(state).asking("api.example.test:443");

        assertThat(only(context).activity()).isEqualTo(TaskInventory.Activity.DEAD);
    }

    @Test
    void aTaskFromAnOlderSokarStillDescribesItself(@TempDir Path dir) throws IOException {

        // No profile file at all. Every added field is absent, and nothing throws: an interface
        // that could not list a running task because it predates a release is worse than one
        // that shows less about it.
        final SokarContext context = context(dir);
        task("sokar-uc-shell-1", "Up 4 minutes", "1788500000", "0");

        assertThat(only(context)).satisfies(task -> {
            assertThat(task.agent()).isNull();
            assertThat(task.mode()).isNull();
            assertThat(task.name()).isEqualTo("sokar-uc-shell-1");
            assertThat(task.running()).isTrue();
        });
    }
}
