package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link TaskListCommand} and {@link TaskStopCommand}.
 */
class TaskLifecycleCommandsTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private SokarContext context(Path dir) {
        root = dir;
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = new CommandLine(new SokarCli(), new SokarFactory(context));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    /**
     * Makes the fake answer the workspace census the stop command runs in the container.
     *
     * @param dir Test directory.
     * @param counts What the census prints: changed files, then unpushed commits.
     */
    private void workspaceReports(Path dir, String counts) {
        runner.answering("git status --porcelain", counts);
    }

    private Path stateOf(String container) throws IOException {
        final Path state = root.resolve("run/sokar").resolve(container);
        Files.createDirectories(state);
        new Sidecar(Sidecar.VERSION, "uc", "guarded", state.resolve("r.nft").toString(),
                state.resolve("dns.conf").toString(), "/usr/bin/sokar", state.toString())
                .writeTo(state.resolve("sidecar.json"));
        return state;
    }

    @Test
    void listsTasksWithTheProjectTheyBelongTo(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\nnot-sokar-at-all\tUp 2 hours\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "task", "list")).isZero();
        assertThat(out.toString())
                .contains("sokar-uc-shell-1")
                .contains("uc")
                .contains("guarded")
                .contains("Up 4 minutes");
        assertThat(out.toString()).as("another user's container is not Sokar's business")
                .doesNotContain("not-sokar-at-all");
    }

    @Test
    void aStoppedTaskStaysInsideItsColumn(@TempDir Path dir) throws IOException {

        // The runtime's longest phrase is twice the width of the column, and it ran into the
        // helper count: the row stopped being a table exactly when a task had been stopped,
        // which is when an operator is reading this to find the name to resume.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (143) Less than a second ago\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "task", "list")).isZero();

        final String row = out.toString().lines().filter(line -> line.startsWith("sokar-uc"))
                .findFirst().orElseThrow();
        assertThat(row).contains("Exited (143)").doesNotContain("Less than");
        // The helper count still starts where the header says it does.
        assertThat(row.indexOf("0")).isEqualTo(out.toString().lines().findFirst().orElseThrow()
                .indexOf("HELPERS"));
    }

    @Test
    void saysSoWhenThereAreNoTasks(@TempDir Path dir) {

        runner.answering("ps", "");

        assertThat(execute(context(dir), "task", "list")).isZero();
        assertThat(out.toString()).contains("No tasks.");
    }

    @Test
    void stoppingReapsTheHelpersAndSaysHowMany(@TempDir Path dir) throws Exception {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        final Path state = stateOf("sokar-uc-shell-1");
        final Process helper = new ProcessBuilder("sleep", "120").start();
        Files.writeString(state.resolve("vault.pid"), String.valueOf(helper.pid()));

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1")).isZero();

        assertThat(helper.waitFor(10, TimeUnit.SECONDS)).isTrue();
        assertThat(out.toString()).contains("helpers   1 of 1 stopped");
        assertThat(state).as("the logs are what an operator reads afterwards").exists();
    }

    @Test
    void stoppingKeepsTheContainerSoTheTaskCanBeResumed(@TempDir Path dir) throws IOException {

        // Measured: removing it instead threw away the workspace and made resume impossible,
        // while reporting that the task had been stopped.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1")).isZero();

        assertThat(runner.lines())
                .anyMatch(line -> line.startsWith("podman stop") && line.endsWith("sokar-uc-shell-1"));
        assertThat(runner.lines()).as("removing it would destroy the workspace")
                .noneMatch(line -> line.startsWith("podman rm"));
        assertThat(out.toString()).contains("sokar task resume sokar-uc-shell-1");
    }

    @Test
    void purgeRemovesTheContainerAsWell(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isZero();

        assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void stoppingATaskThatHasAlreadyGoneIsNotAnError(@TempDir Path dir) {

        runner.answering("ps", "");

        assertThat(execute(context(dir), "task", "stop", "sokar-uc-shell-404")).isZero();
        assertThat(out.toString()).contains("nothing to stop");
    }

    @Test
    void refusesAContainerSokarDidNotCreate(@TempDir Path dir) {

        // This command removes containers and kills processes; a foreign name is not guessed at.
        assertThat(execute(context(dir), "task", "stop", "somebody-elses-database")).isEqualTo(64);
        assertThat(err.toString()).contains("is not a task Sokar created");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void resumingStartsTheContainerAndItsRecordedHelpers(@TempDir Path dir) throws Exception {

        final SokarContext context = context(dir);
        runner.answering("container inspect", "4711");
        final Path state = stateOf("sokar-uc-shell-1");
        new TaskHelpers(java.util.List.of(
                new TaskHelpers.Helper("vault",
                        java.util.List.of("true", "--token-file", "x"), java.util.Map.of(),
                        TaskHelpers.BEFORE),
                new TaskHelpers.Helper("watcher",
                        java.util.List.of("true", "--pid", "1"), java.util.Map.of(),
                        TaskHelpers.AFTER)))
                .writeTo(state);

        assertThat(execute(context, "task", "resume", "sokar-uc-shell-1")).isZero();
        assertThat(out.toString())
                .contains("started   sokar-uc-shell-1")
                .contains("helpers   2 of 2 started")
                // Resume starts the task; it does not put anybody inside it. Somebody told to
                // "go back in" who got their own prompt back read that as broken, so the next
                // command is printed rather than assumed known.
                .contains("attach    sokar task attach sokar-uc-shell-1");
        assertThat(runner.lines()).anyMatch(line -> line.contains("start sokar-uc-shell-1"));
    }

    @Test
    void aSocketHelperStartsBeforeTheContainer(@TempDir Path dir) throws Exception {

        // A bind mount is bound to the file that exists when the container starts. Measured: with
        // the container first, it held the previous run's deleted socket and every request through
        // it went nowhere, while the same request from the host was answered.
        final SokarContext context = context(dir);
        runner.answering("container inspect", "4711");
        final Path state = stateOf("sokar-uc-shell-1");
        final Path order = dir.resolve("order.txt");
        new TaskHelpers(java.util.List.of(new TaskHelpers.Helper("vault",
                java.util.List.of("/bin/sh", "-c", "echo vault >> " + order),
                java.util.Map.of(), TaskHelpers.BEFORE))).writeTo(state);

        execute(context, "task", "resume", "sokar-uc-shell-1");

        final int podmanStart = runner.lines().indexOf(runner.lines().stream()
                .filter(line -> line.startsWith("podman start")).findFirst().orElseThrow());
        assertThat(podmanStart).isNotNegative();
        for (int i = 0; i < 50 && !Files.exists(order); i++) {
            Thread.sleep(100);
        }
        assertThat(Files.readString(order)).contains("vault");
    }

    @Test
    void resumingSaysSoWhenTheImageHasBeenRebuilt(@TempDir Path dir) throws IOException {

        // Told, not upgraded: everything installed in the container since it started would be lost
        // if resuming swapped the image, which is the opposite of what resuming is for.
        final SokarContext context = context(dir);
        runner.answering("container inspect --format {{.Id}}", "abc123");
        runner.answering("container inspect --format {{.ImageName}}", "sokar/uc\tsha256:old");
        runner.answering("image inspect", "sha256:new");
        runner.answering("container inspect --format {{.State.Pid}}", "4711");
        final Path state = stateOf("sokar-uc-shell-1");
        new TaskHelpers(java.util.List.of(new TaskHelpers.Helper("watcher",
                java.util.List.of("true"), java.util.Map.of(), TaskHelpers.AFTER)))
                .writeTo(state);

        execute(context, "task", "resume", "sokar-uc-shell-1");

        assertThat(out.toString()).contains("has been rebuilt since this task started");
    }

    @Test
    void resumingSaysNothingWhenTheImageIsUnchanged(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("container inspect --format {{.Id}}", "abc123");
        runner.answering("container inspect --format {{.ImageName}}", "sokar/uc\tsha256:same");
        runner.answering("image inspect", "sha256:same");
        runner.answering("container inspect --format {{.State.Pid}}", "4711");
        final Path state = stateOf("sokar-uc-shell-1");
        new TaskHelpers(java.util.List.of(new TaskHelpers.Helper("watcher",
                java.util.List.of("true"), java.util.Map.of(), TaskHelpers.AFTER)))
                .writeTo(state);

        execute(context, "task", "resume", "sokar-uc-shell-1");

        // Asserted positively as well: without this the test passes when resume fails early.
        assertThat(out.toString()).contains("started   sokar-uc-shell-1");
        assertThat(out.toString()).doesNotContain("rebuilt");
    }

    @Test
    void resumingATaskThatIsAlreadyUpStartsNothing(@TempDir Path dir) throws Exception {

        // Measured on a real container: two resumes of a running task left two 'shield watch'
        // processes re-parented to init. Each helper of a given name writes the same pid file,
        // so the second one leaves the first named by nothing, and 'task stop' then reported
        // "4 of 4 stopped" while they went on running.
        final SokarContext context = context(dir);
        runner.answering("container inspect", "4711");
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        final Path state = stateOf("sokar-uc-shell-1");
        new TaskHelpers(java.util.List.of(new TaskHelpers.Helper("watcher",
                java.util.List.of("sleep", "30"), java.util.Map.of(), TaskHelpers.AFTER)))
                .writeTo(state);

        assertThat(execute(context, "task", "resume", "sokar-uc-shell-1")).isZero();

        assertThat(out.toString()).contains("already up; nothing to resume");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman start"));
    }

    @Test
    void resumingSomethingThatIsNotThereFails(@TempDir Path dir) {

        runner.failing("container inspect", 125, "no such container");

        assertThat(execute(context(dir), "task", "resume", "sokar-uc-shell-404")).isEqualTo(69);
        assertThat(err.toString()).contains("no container");
    }

    @Test
    void aResumedProxyKeepsTheTokenTheContainerAlreadyHolds(@TempDir Path dir) throws Exception {

        // The container's environment is fixed when it is created: a freshly minted token would
        // be rejected by the proxy and would read as a bad credential.
        final SokarContext context = context(dir);
        runner.answering("container inspect", "4711");
        final Path state = stateOf("sokar-uc-shell-1");
        final Path marker = dir.resolve("argv.txt");
        new TaskHelpers(java.util.List.of(new TaskHelpers.Helper("vault",
                java.util.List.of("/bin/sh", "-c", "echo \"$@\" > " + marker + "", "sh"),
                java.util.Map.of(), TaskHelpers.BEFORE))).writeTo(state);

        execute(context, "task", "resume", "sokar-uc-shell-1");

        // The process is started detached, so wait for the file it writes.
        for (int i = 0; i < 50 && !Files.exists(marker); i++) {
            Thread.sleep(100);
        }
        assertThat(Files.readString(marker)).contains("--reuse-token");
    }

    @Test
    void purgeRefusesWhenWorkWouldBeLost(@TempDir Path dir) throws IOException {

        // The harm this exists for: removal is a cleanup command, and it used to destroy work
        // that existed nowhere else without saying anything.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "3 2");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isEqualTo(65);
        assertThat(err.toString())
                .contains("2 commits and 3 changed files")
                .contains("--rescue")
                .contains("--force");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void aStoppedTaskIsNotPurgedOnWhatNobodyCanSee(@TempDir Path dir) throws IOException {

        // The same harm, one command later: nothing can look inside a stopped container, so
        // before this the refusal simply did not apply and the work went quietly.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 2 minutes ago\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isEqualTo(65);
        assertThat(err.toString()).contains("nothing recorded what it holds").contains("resume");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void aStoppingTaskWritesDownWhatItHolds(@TempDir Path dir) throws IOException {

        // Written on the way down, because that is the last moment anyone can ask.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        final Path state = stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "3 2");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1")).isZero();

        assertThat(state.resolve(UnhandedWork.FILE)).content()
                .isEqualTo("2 commits and 3 changed files");
    }

    @Test
    void theNoteIsWhatRefusesToRemoveAStoppedTask(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 2 minutes ago\n");
        final Path state = stateOf("sokar-uc-shell-1");
        UnhandedWork.note(state, "2 commits");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isEqualTo(65);
        assertThat(err.toString()).contains("it holds 2 commits");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void aTaskThatHeldNothingIsRemovedWithoutFuss(@TempDir Path dir) throws IOException {

        // "This held nothing" and "nobody looked" are different answers, and only the first one
        // makes removal safe without asking.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 2 minutes ago\n");
        UnhandedWork.note(stateOf("sokar-uc-shell-1"), null);

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isZero();
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void rescuingAStoppedTaskSaysToResumeItFirst(@TempDir Path dir) throws IOException {

        // Rescue pushes from inside the container to the gate, and neither is up.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 2 minutes ago\n");
        UnhandedWork.note(stateOf("sokar-uc-shell-1"), "2 commits");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge", "--rescue"))
                .isEqualTo(70);
        assertThat(err.toString()).contains("task resume");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void purgingATaskThatIsGoneEntirelyIsStillNotAnError(@TempDir Path dir) {

        final SokarContext context = context(dir);
        runner.answering("ps", "");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isZero();
        assertThat(out.toString()).contains("nothing to stop");
    }

    @Test
    void aRescueThatPushedNothingIsNotARescue(@TempDir Path dir) throws IOException {

        // Measured: a workspace whose repository had no initial commit printed "nothing to push",
        // exited zero, and the container was removed as rescued while the mirror never saw a ref.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        // Registered before the ref, and matched on the commit message: answers are tried in
        // insertion order, and the push command mentions SOKAR_TASK_REF as well.
        runner.answering("agent: uncommitted work", "nothing to push");
        runner.answering("SOKAR_TASK_REF", "refs/sokar/incoming/shell");
        stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "3 0");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge", "--rescue"))
                .isEqualTo(70);
        assertThat(err.toString()).contains("could not push the work");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void forceRemovesItAnyway(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "3 2");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge", "--force"))
                .isZero();
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void aCleanWorkspaceIsNotInTheWay(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "0 0");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isZero();
        assertThat(out.toString()).doesNotContain("work      ");
    }

    @Test
    void saysWhatARemovalDestroysInsideTheContainer(@TempDir Path dir) throws IOException {

        // The workspace has the gate to arrive in; what the agent installed inside the container
        // has nowhere at all, and nothing else records that it existed. Counted before the
        // removal, because afterwards there is nothing left to ask.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 3 minutes ago\n");
        runner.answering("diff", "C /etc\nA /opt/tool\nA /opt/tool/bin\nC /var\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge", "--force"))
                .isZero();

        // Added paths only: a container that ran at all has changed /etc and /var, and a number
        // that is never zero means nothing.
        assertThat(out.toString()).contains("discarded 2 files the agent added");
    }

    @Test
    void rescuePushesTheWorkUnderItsOwnRef(@TempDir Path dir) throws IOException {

        // Rescued work is not work an agent offered up, so it lands beside the reviewed ref
        // rather than in it.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        runner.answering("SOKAR_TASK_REF", "refs/sokar/incoming/shell");
        stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "3 2");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge", "--rescue"))
                .isZero();

        assertThat(out.toString()).contains("rescued   refs/sokar/incoming/shell-rescued");
        // In the environment of the exec, not in its arguments: podman is told the name and
        // copies the value out of the environment it was started with.
        assertThat(runner.invocations())
                .anyMatch(command -> "refs/sokar/incoming/shell-rescued"
                        .equals(command.environment().get("SOKAR_TASK_REF")));
        assertThat(runner.lines()).noneMatch(line -> line.contains("shell-rescued"));
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void aTaskThatPushesStraightToItsUpstreamCannotBeRescued(@TempDir Path dir) throws IOException {

        // Rescuing it would publish unreviewed work, which is the one thing the gate exists
        // to prevent.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        runner.answering("SOKAR_TASK_REF", "refs/heads/shell");
        stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "0 2");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge", "--rescue"))
                .isEqualTo(70);
        assertThat(err.toString()).contains("without publishing it");
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void purgeRemovesTheStateDirectory(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");
        final Path state = stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isZero();
        assertThat(state).doesNotExist();
    }
}
