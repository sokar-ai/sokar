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
        final CommandLine cmd = SokarCli.commandLine(context);
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
    void stillNamesTheProjectAfterTheRuntimeDirectoryIsGone(@TempDir Path dir) {

        // The reported case. The sidecar holding project and class lives in $XDG_RUNTIME_DIR,
        // which the system destroys when the user's last session ends, while podman's own store
        // survives - so after a reboot every task that outlived it listed "-" for both. No
        // stateOf() here on purpose: that is what a rebooted machine looks like.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (143)\t1700000000\t1700000100\tuc\tguarded\n");

        assertThat(execute(context, "task", "list")).isZero();

        final String row = out.toString().lines().filter(line -> line.startsWith("sokar-uc"))
                .findFirst().orElseThrow();
        assertThat(row).contains("uc").contains("guarded").doesNotContain("-  ");
    }

    @Test
    void namesTheResumableTasksWhenNoneWasGiven(@TempDir Path dir) {

        // Asked for after 'sokar task resume' answered "Missing required parameter: TASK" on a
        // machine where Sokar knew exactly which containers could have been resumed. The name is
        // not the hard part of the job; finding it is.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (143)\t1700000000\t1700000100\t\n"
                + "sokar-uc-build-2\tUp 4 minutes\t1700000000\t0\t\n");

        assertThat(execute(context, "task", "resume")).isNotZero();

        // The stopped one only: resuming a running task does nothing, so offering it would be
        // offering a command with no effect.
        assertThat(err.toString()).contains("sokar-uc-shell-1").doesNotContain("sokar-uc-build-2");
    }

    @Test
    void namesTheRunningTasksWhenAttachWasGivenNoName(@TempDir Path dir) {

        // The other way round, and the reason one list for every command would be wrong twice.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (143)\t1700000000\t1700000100\t\n"
                + "sokar-uc-build-2\tUp 4 minutes\t1700000000\t0\t\n");

        assertThat(execute(context, "task", "attach")).isNotZero();

        assertThat(err.toString()).contains("sokar-uc-build-2").doesNotContain("sokar-uc-shell-1");
    }

    @Test
    void doesNotOfferALoginContainerAsATask(@TempDir Path dir) {

        // A completion built on the 'sokar-' prefix would put the throwaway containers 'vault
        // login' creates back in front of somebody - the same defect that put them in task list.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (143)\t1700000000\t1700000100\t\n"
                + "sokar-login-1788886971400\tExited (0)\t1700000000\t1700000100\t\n");

        assertThat(execute(context, "task", "resume")).isNotZero();

        assertThat(err.toString()).contains("sokar-uc-shell-1").doesNotContain("sokar-login-");
    }

    @Test
    void saysWhatARunningTaskHoldsWithoutTryingToDestroyIt(@TempDir Path dir) throws IOException {

        // The whole reason this command exists: uncommitted work used to be reachable only by
        // running 'task stop --purge' and reading the refusal, which is a destructive command
        // used as a query.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");
        stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "3 2");

        assertThat(execute(context, "task", "status", "sokar-uc-shell-1")).isZero();

        assertThat(out.toString())
                .contains("sokar-uc-shell-1")
                .contains("uc")
                .contains("guarded")
                .contains("never pushed to the gate");
        // The verbs, not substrings: "--format" contains "rm", which is the same trap already
        // written down about matching "rm --force" by substring.
        assertThat(runner.lines()).as("a query changes nothing")
                .noneMatch(line -> line.startsWith("podman rm")
                        || line.startsWith("podman stop") || line.startsWith("podman kill"));
    }

    @Test
    void paintsNothingWhenTheOutputIsNotATerminal(@TempDir Path dir) throws IOException {

        // The colour marks work that exists nowhere else, and it must reach a person's terminal
        // and nowhere else: escapes in a pipe, a log or a fixture are noise, and this output is
        // read by scripts too. picocli's Ansi.AUTO decides, which is why it is used rather than
        // escapes written by hand.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\n");
        stateOf("sokar-uc-shell-1");
        workspaceReports(dir, "3 2");

        execute(context, "task", "status", "sokar-uc-shell-1");

        assertThat(out.toString()).contains("never pushed to the gate").doesNotContain("\u001b[");
    }

    @Test
    void doesNotCallAStoppedTasksWorkspaceEmpty(@TempDir Path dir) throws IOException {

        // The workspace is inside the container, so once it is stopped nothing can read it. That
        // is not the same as holding nothing, and reporting "nothing" would read as nothing to
        // lose - about a task whose workspace may be full of work.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (143)\t1700000000\t1700000100\tuc\tguarded\n");
        stateOf("sokar-uc-shell-1");

        assertThat(execute(context, "task", "status", "sokar-uc-shell-1")).isZero();

        assertThat(out.toString()).contains("cannot be read").contains("left no record");
    }

    @Test
    void namesTheTasksWhenStatusWasAskedAboutOneThatIsNotThere(@TempDir Path dir) {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\n");

        assertThat(execute(context, "task", "status", "sokar-uc-shell-404")).isEqualTo(69);
        assertThat(err.toString()).contains("sokar-uc-shell-1");
    }

    @Test
    void saysATaskPredatesARestartRatherThanLettingPodmanExplain(@TempDir Path dir) {

        // Reported: 'task resume' on a task from before a reboot answered sixteen frames of
        // picocli wrapping crun's "cannot stat .../vault.sock". The state directory is under
        // $XDG_RUNTIME_DIR, which the system clears on restart, and the container bind-mounts
        // the broker socket out of it - so podman cannot start it and no helper can be started
        // that would bring the socket back. No state directory is exactly that situation.
        final SokarContext context = context(dir);
        runner.answering("container inspect", "c0ffee\n");
        runner.answering("ps", "sokar-uc-shell-1\tExited (143)\t1700000000\t1700000100\t\n");

        assertThat(execute(context, "task", "resume", "sokar-uc-shell-1")).isEqualTo(69);

        assertThat(err.toString())
                .contains("before this machine restarted")
                .contains("podman cp sokar-uc-shell-1:/workspace")
                .as("the workspace survives even though the container cannot start")
                .contains("--purge --force");
        assertThat(runner.lines()).as("podman is not asked to do something that cannot work")
                .noneMatch(line -> line.startsWith("podman start"));
    }

    @Test
    void listsTheLogsATaskHasRatherThanTheOnesItMightHave(@TempDir Path dir) throws IOException {

        // Which files exist depends on what the task started: no gate means no gate.log. Listing
        // a fixed set would send somebody to open one that was never going to be there.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\n");
        final Path state = stateOf("sokar-uc-shell-1");
        Files.writeString(state.resolve("gate.log"), "the gate said something\n");
        Files.writeString(state.resolve("vault.log"), "the broker said something\n");
        Files.writeString(state.resolve("watcher.pid"), "4711");

        assertThat(execute(context, "task", "logs", "sokar-uc-shell-1")).isZero();

        assertThat(out.toString()).contains("gate.log").contains("vault.log")
                .as("only logs").doesNotContain("watcher.pid");
    }

    @Test
    void countsTheTwoLogsThatDoNotSayLogInTheirName(@TempDir Path dir) throws IOException {

        // Reported: the list was short. 'events.jsonl' is what the firewall blocked - exactly
        // what somebody debugging a task that started and did nothing needs - and 'reader.err'
        // is where the reader hook's own failures go. A suffix rule hid both.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\n");
        final Path state = stateOf("sokar-uc-shell-1");
        Files.writeString(state.resolve("events.jsonl"), "{\"host\":\"example.com\"}\n");
        Files.writeString(state.resolve("reader.err"), "the reader complained\n");

        assertThat(execute(context, "task", "logs", "sokar-uc-shell-1")).isZero();

        assertThat(out.toString()).contains("events.jsonl").contains("reader.err");
    }

    @Test
    void neverOffersOrReadsTheTasksOwnToken(@TempDir Path dir) throws IOException {

        // The state directory holds the live phantom token beside the logs, with the sockets,
        // the pid files and the ruleset. This is why the rule is an allow-list: "everything that
        // is not a secret" has to be right forever, including about files a later release adds.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\n");
        final Path state = stateOf("sokar-uc-shell-1");
        Files.writeString(state.resolve("vault.token"), "sokar_pt_the_real_thing\n");
        Files.writeString(state.resolve("sidecar.json"), "{}");
        Files.writeString(state.resolve("gate.log"), "a line\n");

        assertThat(execute(context, "task", "logs", "sokar-uc-shell-1")).isZero();
        assertThat(out.toString()).contains("gate.log")
                .doesNotContain("vault.token").doesNotContain("sidecar.json");

        assertThat(execute(context, "task", "logs", "sokar-uc-shell-1", "vault.token"))
                .isEqualTo(69);
        assertThat(out.toString()).doesNotContain("sokar_pt_the_real_thing");
    }

    @Test
    void saysWhatTheTwoLogsHoldWhoseNamesDoNotSay(@TempDir Path dir) throws IOException {

        // 'events.jsonl' is the file to read when a task starts and then does nothing, and a
        // person reading the list has no way to know that from the name. The sentence lives here
        // rather than in a client: a table at the other end drifts the day a file is added.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\t\n");
        final Path state = stateOf("sokar-uc-shell-1");
        Files.writeString(state.resolve("events.jsonl"), "");
        Files.writeString(state.resolve("reader.err"), "");
        Files.writeString(state.resolve("gate.log"), "a line\n");

        final java.util.List<TaskInventory.Log> logs =
                new TaskInventory(context).logs("sokar-uc-shell-1");

        assertThat(logs).filteredOn(log -> log.name().equals("events.jsonl"))
                .singleElement().extracting(TaskInventory.Log::what).asString()
                .contains("firewall stopped");
        assertThat(logs).filteredOn(log -> log.name().equals("reader.err"))
                .singleElement().extracting(TaskInventory.Log::what).asString()
                .as("the consequence, not the description: empty is the normal case")
                .contains("may be incomplete");
        // And nothing for a name that speaks for itself. A sentence saying gate.log is the gate's
        // log is noise, and noise beside the two that matter is what stops them being read.
        assertThat(logs).filteredOn(log -> log.name().equals("gate.log"))
                .singleElement().extracting(TaskInventory.Log::what).isNull();
    }

    @Test
    void leavesAnAbsentDescriptionOutRatherThanSendingItEmpty(@TempDir Path dir)
            throws IOException {

        // A client draws absent as nothing; an empty string under a name reads as a description
        // that failed instead of one nobody gave. The interface built its reader on that.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\t\n");
        Files.writeString(stateOf("sokar-uc-shell-1").resolve("gate.log"), "a line\n");

        assertThat(new TaskInventory(context).logs("sokar-uc-shell-1").getFirst().asMap())
                .doesNotContainKey("what");
    }

    @Test
    void printsTheLogSomebodyAskedFor(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\n");
        Files.writeString(stateOf("sokar-uc-shell-1").resolve("gate.log"), "a line\nanother\n");

        assertThat(execute(context, "task", "logs", "sokar-uc-shell-1", "gate.log")).isZero();

        assertThat(out.toString()).contains("a line").contains("another");
    }

    @Test
    void refusesANameThatIsNotOneOfThatTasksLogs(@TempDir Path dir) throws IOException {

        // Checked against the set this task actually has, so there is no traversal to reason
        // about: anything outside a known answer is simply not one.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\n");
        Files.writeString(stateOf("sokar-uc-shell-1").resolve("gate.log"), "a line\n");

        assertThat(execute(context, "task", "logs", "sokar-uc-shell-1", "../../../etc/passwd"))
                .isEqualTo(69);
        assertThat(err.toString()).contains("has no log called").contains("gate.log");
        assertThat(out.toString()).doesNotContain("root:");
    }

    @Test
    void tellsANameThatNamesNothingApartFromATaskWithNoLogs(@TempDir Path dir) {

        // Reported: 'task logs sokar-does-not' answered "either nothing wrote one, or the machine
        // has restarted since it ran" - which describes a task that exists, about one that never
        // did. The shape of the name is not evidence that it is a task.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\n");

        assertThat(execute(context, "task", "logs", "sokar-does-not")).isEqualTo(69);

        assertThat(err.toString()).contains("there is no task called sokar-does-not")
                .as("and the ones that do exist").contains("sokar-uc-shell-1");
        assertThat(out.toString()).doesNotContain("machine has restarted");
    }

    @Test
    void stillReadsTheLogsOfAContainerSomebodyRemovedByHand(@TempDir Path dir) throws IOException {

        // Existing OR having logs, not existing alone: a removed container can leave its state
        // directory behind, and those logs are worth reading precisely then.
        final SokarContext context = context(dir);
        runner.answering("ps", "");
        Files.writeString(stateOf("sokar-uc-shell-1").resolve("gate.log"), "what happened\n");

        assertThat(execute(context, "task", "logs", "sokar-uc-shell-1", "gate.log")).isZero();

        assertThat(out.toString()).contains("what happened");
    }

    @Test
    void saysWhyThereAreNoLogsRatherThanPrintingNothing(@TempDir Path dir) {

        // A task whose state directory went with a restart looks exactly like one that never
        // wrote anything, and an empty answer would let somebody read it as "nothing happened".
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (143)\t1700000000\t1700000100\t\n");

        assertThat(execute(context, "task", "logs", "sokar-uc-shell-1")).isZero();

        assertThat(out.toString()).contains("no logs").contains("machine has restarted");
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
        // Listing Sokar's own tasks to offer them is reading, not touching. What must not happen
        // is this command doing anything TO the machine on a name that is not Sokar's.
        assertThat(runner.lines()).allMatch(line -> line.startsWith("podman ps"));
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

        // Through the reader rather than against the bytes: what matters is that a later
        // removal gets the same two numbers back, not how they were spelled on disk.
        final UnhandedWork.Held read = UnhandedWork.read(state);
        assertThat(read.readable()).isTrue();
        assertThat(read.changedFiles()).isEqualTo(3);
        assertThat(read.unpushedCommits()).isEqualTo(2);
        assertThat(read.asOf()).as("a recorded answer says when it was true").isNotNull();
    }

    @Test
    void theNoteIsWhatRefusesToRemoveAStoppedTask(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 2 minutes ago\n");
        final Path state = stateOf("sokar-uc-shell-1");
        UnhandedWork.note(state, new UnhandedWork.Held(true, 0, 2, null));

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
        UnhandedWork.note(stateOf("sokar-uc-shell-1"), new UnhandedWork.Held(true, 0, 0, null));

        assertThat(execute(context, "task", "stop", "sokar-uc-shell-1", "--purge")).isZero();
        assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void rescuingAStoppedTaskSaysToResumeItFirst(@TempDir Path dir) throws IOException {

        // Rescue pushes from inside the container to the gate, and neither is up.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 2 minutes ago\n");
        UnhandedWork.note(stateOf("sokar-uc-shell-1"), new UnhandedWork.Held(true, 0, 2, null));

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
