package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Sidecar;
import org.fuin.sokar.wire.TaskMode;
import org.fuin.sokar.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for changing enforcement on a task that is already running.
 * <p>
 * The state used to be chosen when a task started and never again, so what is asserted here is
 * mostly what must <em>not</em> happen: a change recorded for a task that is not running, a
 * watcher left behind when enforcement is turned off, and a resume that would bring back the mode
 * somebody just changed away from.
 */
class RunningClearanceTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private static final String CONTAINER = "sokar-demo-shell-1";

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    /** A task that exists, with a profile and a sidecar, as a started one has. */
    private Path task(Path dir, String clearance) throws Exception {
        final SokarContext context = context(dir);
        final Path state = context.paths().tasks().containerState(CONTAINER);
        Files.createDirectories(state);
        new TaskProfile(1, "claude", TaskMode.SHELL, null, null,
                java.time.Instant.now().toString(), clearance).writeTo(state);
        new Sidecar(Sidecar.VERSION, "demo", "guarded", state.resolve("ruleset.nft").toString(),
                state.resolve("dns.conf").toString(), "/usr/bin/sokar", state.toString())
                .writeTo(state.resolve("sidecar.json"));
        return state;
    }

    private RunningClearance.Result set(Path dir, String mode, boolean dryRun) {
        return RunningClearance.set(context(dir), CONTAINER, mode, dryRun,
                new PrintWriter(out, true));
    }

    @Test
    void aModeNobodyKnowsIsRefusedAndNamesTheOnesThatExist(@TempDir Path dir) throws Exception {

        task(dir, "prompt");

        final RunningClearance.Result result = set(dir, "quiet", false);

        assertThat(result.outcome()).isEqualTo(RunningClearance.Outcome.UNKNOWN_MODE);
        assertThat(result.detail()).contains("prompt").contains("off");
    }

    @Test
    void changingToTheModeItIsAlreadyInRestartsNothing(@TempDir Path dir) throws Exception {

        // Restarting a watcher to arrive at the mode it was already in would put a gap in
        // enforcement for no reason at all.
        task(dir, "prompt");

        assertThat(set(dir, "prompt", false).outcome())
                .isEqualTo(RunningClearance.Outcome.UNCHANGED);
    }

    @Test
    void turningItOffStopsTheWatcherAndTakesItOutOfWhatAResumeWouldRestart(@TempDir Path dir)
            throws Exception {

        // The second half is the one that would have been missed: a resume rebuilds helpers from
        // resume.json, so leaving the watcher there would bring back the mode somebody just
        // turned off - silently, and only after a restart.
        final Path state = task(dir, "prompt");
        // A real process, recorded as a helper records itself: a bare id is no longer enough to
        // be signalled, because an id on its own names whatever holds it now.
        final Process watcher = new ProcessBuilder("sleep", "120").start();
        org.fuin.sokar.wire.HelperPid.record(state.resolve("watcher.pid"), watcher.toHandle());
        new TaskHelpers(java.util.List.of(new TaskHelpers.Helper("watcher",
                java.util.List.of("sokar", "shield", "watch"), java.util.Map.of(),
                TaskHelpers.AFTER))).writeTo(state);

        final RunningClearance.Result result = set(dir, "off", false);

        assertThat(result.outcome()).isEqualTo(RunningClearance.Outcome.CHANGED);
        assertThat(result.was()).isEqualTo("prompt");
        assertThat(runner.invocations()).anySatisfy(command ->
                assertThat(command.arguments())
                        .containsExactly("kill", String.valueOf(watcher.pid())));
        assertThat(TaskHelpers.readFrom(state).helpers()).isEmpty();
        assertThat(TaskProfile.readFrom(state).clearance()).isEqualTo("off");
    }

    @Test
    void aPreviewChangesNothingAtAll(@TempDir Path dir) throws Exception {

        final Path state = task(dir, "prompt");
        // A real process, recorded as a helper records itself: a bare id is no longer enough to
        // be signalled, because an id on its own names whatever holds it now.
        final Process watcher = new ProcessBuilder("sleep", "120").start();
        org.fuin.sokar.wire.HelperPid.record(state.resolve("watcher.pid"), watcher.toHandle());

        final RunningClearance.Result result = set(dir, "off", true);

        assertThat(result.outcome()).isEqualTo(RunningClearance.Outcome.PREVIEWED);
        assertThat(state.resolve("watcher.pid")).exists();
        assertThat(TaskProfile.readFrom(state).clearance()).isEqualTo("prompt");
        assertThat(runner.invocations()).noneSatisfy(command ->
                assertThat(command.arguments()).contains("kill"));
    }

    @Test
    void aTaskNothingKnowsIsRefusedRatherThanCreated(@TempDir Path dir) {

        assertThat(set(dir, "off", false).outcome())
                .isEqualTo(RunningClearance.Outcome.NO_SUCH_TASK);
    }

    @Test
    void aTaskStartedByASokarThatRecordedNoProfileSaysSo(@TempDir Path dir) throws Exception {

        // Changing it without a profile would mean inventing the rest of one, which would claim a
        // mode and a start time nobody recorded.
        Files.createDirectories(context(dir).paths().tasks().containerState(CONTAINER));

        assertThat(set(dir, "off", false).outcome())
                .isEqualTo(RunningClearance.Outcome.NOT_RECORDED);
    }

    @Test
    void theTaskNameIsReadWithTheProjectRatherThanBySplittingOnHyphens() {

        // A project name may contain hyphens, so this is only answerable once the project is
        // known. Splitting on the first hyphen would name the wrong task for every project whose
        // name has one, and the prompt would then be about something that does not exist.
        assertThat(RunningClearance.taskOf("sokar-my-app-review-1", "my-app")).isEqualTo("review");
        // A task name with a hyphen of its own: only the last one separates the run id, and
        // splitting on the first would name "code" for a task called "code-review".
        assertThat(RunningClearance.taskOf("sokar-demo-code-review-1", "demo"))
                .isEqualTo("code-review");
        assertThat(RunningClearance.taskOf("sokar-demo-shell-1", "demo")).isEqualTo("shell");
        assertThat(RunningClearance.taskOf("something-else", "demo")).isEqualTo("something-else");
    }
}
