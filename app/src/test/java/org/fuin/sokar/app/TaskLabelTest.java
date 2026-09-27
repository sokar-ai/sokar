package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.TaskMode;
import org.fuin.sokar.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for giving a task a caption.
 * <p>
 * What has to stay true is that the identity does not move: a caption is read in a list, and the
 * name is what every method takes and what somebody types at the machine.
 */
class TaskLabelTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private Path task(Path dir, String container) throws IOException {
        final Path state = dir.resolve("run/sokar").resolve(container);
        Files.createDirectories(state);
        new TaskProfile(TaskProfile.VERSION, "example", TaskMode.UNATTENDED, "fix the parser",
                "sokar/work", "2026-09-08T06:00:00Z", "prompt").writeTo(state);
        return state;
    }

    @Test
    void writesACaptionIntoTheTasksOwnProfile(@TempDir Path dir) throws IOException {

        // In the profile beside the mode and the prompt, so it survives a resume and outlives the
        // container - exactly as durable as the other things a task says about itself.
        final SokarContext context = context(dir);
        final Path state = task(dir, "sokar-uc-shell-1");

        assertThat(new TaskControl(context).label("sokar-uc-shell-1", "schema migration"))
                .isEqualTo(TaskControl.Labelled.LABELLED);

        assertThat(TaskProfile.readFrom(state).label()).isEqualTo("schema migration");
    }

    @Test
    void changesNothingAboutTheIdentity(@TempDir Path dir) throws IOException {

        // The whole reason this is a caption and not a rename. Everything that identifies the
        // task has to read exactly as before: the container name, the ref its work goes to, and
        // where its state lives.
        final SokarContext context = context(dir);
        final Path state = task(dir, "sokar-uc-shell-1");
        final TaskProfile before = TaskProfile.readFrom(state);

        new TaskControl(context).label("sokar-uc-shell-1", "second attempt");

        final TaskProfile after = TaskProfile.readFrom(state);
        assertThat(after.branch()).as("the ref its work goes to").isEqualTo(before.branch());
        assertThat(after.agent()).isEqualTo(before.agent());
        assertThat(after.mode()).isEqualTo(before.mode());
        assertThat(after.prompt()).isEqualTo(before.prompt());
        assertThat(after.startedAt()).isEqualTo(before.startedAt());
        assertThat(Files.isDirectory(state)).as("its state is where it was").isTrue();
    }

    @Test
    void anEmptyCaptionClearsItRatherThanStoringSpaces(@TempDir Path dir) throws IOException {

        // A label of spaces is a row with an invisible caption and no way to tell it from one
        // nobody set. Absent and empty are the same state on purpose.
        final SokarContext context = context(dir);
        final Path state = task(dir, "sokar-uc-shell-1");
        final TaskControl control = new TaskControl(context);
        control.label("sokar-uc-shell-1", "something");

        assertThat(control.label("sokar-uc-shell-1", "   "))
                .isEqualTo(TaskControl.Labelled.CLEARED);
        assertThat(TaskProfile.readFrom(state).label()).isNull();
    }

    @Test
    void aTaskWithNoProfileSaysSoRatherThanInventingOne(@TempDir Path dir) throws IOException {

        // Writing one here would claim a mode and a start time nobody recorded. An operator
        // cannot fix this by typing it differently, so it must not read like a typo.
        final SokarContext context = context(dir);
        Files.createDirectories(dir.resolve("run/sokar/sokar-uc-shell-1"));

        assertThat(new TaskControl(context).label("sokar-uc-shell-1", "anything"))
                .isEqualTo(TaskControl.Labelled.NOT_RECORDED);
    }

    @Test
    void somethingThatIsNotATaskIsRefused(@TempDir Path dir) {

        assertThat(new TaskControl(context(dir)).label("not-a-sokar-container", "x"))
                .isEqualTo(TaskControl.Labelled.NOT_A_TASK);
    }

    @Test
    void aLabelSurvivesBeingReadBackAsATask(@TempDir Path dir) throws IOException {

        // The end-to-end claim: what was written is what a listing reports, under the key the
        // contract declares.
        final SokarContext context = context(dir);
        task(dir, "sokar-uc-shell-1");
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        new TaskControl(context).label("sokar-uc-shell-1", "schema migration");

        assertThat(new TaskInventory(context).tasks()).singleElement()
                .satisfies(t -> {
                    assertThat(t.label()).isEqualTo("schema migration");
                    assertThat(t.name()).as("the name is untouched").isEqualTo("sokar-uc-shell-1");
                    assertThat(t.asMap()).containsEntry("label", "schema migration");
                });
    }

    @Test
    void aTaskNobodyLabelledReportsAnEmptyStringNotNull(@TempDir Path dir) throws IOException {

        // Every task by default. "" and never the four characters "null", which would render as
        // a caption somebody had chosen.
        final SokarContext context = context(dir);
        task(dir, "sokar-uc-shell-1");
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        assertThat(new TaskInventory(context).tasks()).singleElement()
                .satisfies(t -> assertThat(t.asMap()).containsEntry("label", ""));
    }

    @Test
    void aTaskSaysWhetherItsOwnWorkIsWaitingAtTheGate(@TempDir Path dir) throws IOException {

        // Whether anything of this task is waiting for review cannot be joined from
        // the outside: Task.name is a CONTAINER name and PendingPush.name is a TASK name, and
        // several containers over time share one ref - so the answer has to come from here.
        final SokarContext context = context(dir);
        final Path state = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(state);
        new org.fuin.sokar.wire.Sidecar(org.fuin.sokar.wire.Sidecar.VERSION, "uc", "guarded",
                state.resolve("r.nft").toString(), state.resolve("dns.conf").toString(),
                "/usr/bin/sokar", state.toString()).writeTo(state.resolve("sidecar.json"));
        new TaskProfile(TaskProfile.VERSION, "example", TaskMode.SHELL, null,
                "refs/sokar/incoming/shell", "2026-09-08T06:00:00Z", "prompt").writeTo(state);
        Files.createDirectories(dir.resolve("data/sokar/mirrors/uc.git"));
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        runner.answering("for-each-ref", "refs/sokar/incoming/shell\n");

        assertThat(new TaskInventory(context).tasks()).singleElement()
                .satisfies(t -> assertThat(t.asMap()).containsEntry("waiting", 1));
    }

    @Test
    void aTaskWhoseRefIsNotWaitingSaysZero(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        final Path state = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(state);
        new org.fuin.sokar.wire.Sidecar(org.fuin.sokar.wire.Sidecar.VERSION, "uc", "guarded",
                state.resolve("r.nft").toString(), state.resolve("dns.conf").toString(),
                "/usr/bin/sokar", state.toString()).writeTo(state.resolve("sidecar.json"));
        new TaskProfile(TaskProfile.VERSION, "example", TaskMode.SHELL, null,
                "refs/sokar/incoming/shell", "2026-09-08T06:00:00Z", "prompt").writeTo(state);
        Files.createDirectories(dir.resolve("data/sokar/mirrors/uc.git"));
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        runner.answering("for-each-ref", "refs/sokar/incoming/somebody-else\n");

        assertThat(new TaskInventory(context).tasks()).singleElement()
                .satisfies(t -> assertThat(t.asMap()).containsEntry("waiting", 0));
    }

    @Test
    void anOnlineTaskIsNeverWaiting(@TempDir Path dir) throws IOException {

        // Its ref is refs/heads/<task> and nothing is ever reviewed. Zero here is not a smaller
        // number, it is a question the class does not have - and matching on the ref's tail would
        // have claimed work was waiting for a name that coincided.
        final SokarContext context = context(dir);
        final Path state = dir.resolve("run/sokar/sokar-uc-shell-1");
        Files.createDirectories(state);
        new org.fuin.sokar.wire.Sidecar(org.fuin.sokar.wire.Sidecar.VERSION, "uc", "online",
                state.resolve("r.nft").toString(), state.resolve("dns.conf").toString(),
                "/usr/bin/sokar", state.toString()).writeTo(state.resolve("sidecar.json"));
        new TaskProfile(TaskProfile.VERSION, "example", TaskMode.SHELL, null,
                "refs/heads/shell", "2026-09-08T06:00:00Z", "prompt").writeTo(state);
        Files.createDirectories(dir.resolve("data/sokar/mirrors/uc.git"));
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        runner.answering("for-each-ref", "refs/sokar/incoming/shell\n");

        assertThat(new TaskInventory(context).tasks()).singleElement()
                .satisfies(t -> assertThat(t.asMap()).containsEntry("waiting", 0));
    }

    @Test
    void theMirrorIsAskedOncePerProjectHoweverManyTasks(@TempDir Path dir) throws IOException {

        // A listing is read on every change. A git call per task would make it cost what a
        // listing must not - the same reason 'prepared' asks podman once for the whole list.
        final SokarContext context = context(dir);
        for (final String container : java.util.List.of("sokar-uc-a-1", "sokar-uc-b-2",
                "sokar-uc-c-3")) {
            final Path state = dir.resolve("run/sokar").resolve(container);
            Files.createDirectories(state);
            new org.fuin.sokar.wire.Sidecar(org.fuin.sokar.wire.Sidecar.VERSION, "uc", "guarded",
                    state.resolve("r.nft").toString(), state.resolve("dns.conf").toString(),
                    "/usr/bin/sokar", state.toString()).writeTo(state.resolve("sidecar.json"));
            new TaskProfile(TaskProfile.VERSION, "example", TaskMode.SHELL, null,
                    "refs/sokar/incoming/" + container, "2026-09-08T06:00:00Z", "prompt")
                    .writeTo(state);
        }
        Files.createDirectories(dir.resolve("data/sokar/mirrors/uc.git"));
        runner.answering("ps", "sokar-uc-a-1\tUp 4 minutes\n"
                + "sokar-uc-b-2\tUp 4 minutes\nsokar-uc-c-3\tUp 4 minutes\n");
        runner.answering("for-each-ref", "refs/sokar/incoming/sokar-uc-a-1\n");

        assertThat(new TaskInventory(context).tasks()).hasSize(3);
        assertThat(runner.lines().stream().filter(line -> line.contains("for-each-ref")))
                .hasSize(1);
    }
}
