package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link TaskRunCommand}.
 * <p>
 * Everything that touches the machine goes through {@link SokarContext}. An earlier version of
 * this test had none, so running it built images, started containers and then replaced the test
 * process with a shell.
 */
class TaskRunCommandTest {

    private static final String MINIMAL = """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    /** What the attached session exits with. A shell reports its last command's status. */
    private int execExit;

    /** Whether the attached session ends without returning, as it does when it is signalled. */
    private boolean execDies;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    /** podman gives back the id of what it created, which Sokar records as what makes it its task. */
    private final FakeCommandRunner runner = new FakeCommandRunner().answering(" create ", "0123456789abcdef");

    private final List<List<String>> execCalls = new ArrayList<>();

    private Path root;

    private SokarContext context(Path dir, boolean hooksInstalled) throws IOException {
        return context(dir, hooksInstalled, name -> null);
    }

    private SokarContext context(Path dir, boolean hooksInstalled,
            java.util.function.UnaryOperator<String> environment) throws IOException {
        root = dir;
        // A machine Sokar will run on. Without this the fake answers nothing to 'podman version',
        // which is indistinguishable from a podman too old to support - and every run refuses.
        runner.answering("podman version", "5.8.1");
        // No session here ever writes the marker, so the question "did the shell end" has to
        // answer yes for the teardown to run at all. Without this the fake answers every command
        // with success anyway - which happens to be the right answer here, and is pinned rather
        // than relied on.
        runner.answering("test -f", "");
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarPaths paths = new SokarPaths(xdg, dir.resolve("bin"));
        if (hooksInstalled) {
            Files.createDirectories(dir.resolve("bin"));
            for (final String name : List.of("sokar-hook-nft", "sokar-hook-supervisor", "sokar-hook-reader")) {
                final Path binary = dir.resolve("bin").resolve(name);
                Files.writeString(binary, "#!/bin/sh\n");
                binary.toFile().setExecutable(true);
            }
            // Binaries alone are not an installation: podman has to be told to run them, which is
            // what 'sokar setup' writes and what a task run now insists on.
            new org.fuin.sokar.runtime.HookInstaller(paths.hooksDirectory(),
                    paths.binaryDirectory()).install();
        }
        return new SokarContext(runner, paths, arguments -> {
            execCalls.add(arguments);
            if (execDies) {
                // Stands in for the session dying under the command. A signal cannot be sent in
                // a unit test, but it lands on the same branch: the line recording a clean exit
                // is never reached, so the teardown runs with the value it was armed with.
                throw new IllegalStateException("the session died");
            }
            return execExit;
        }, environment);
    }

    /** The hook binaries without the descriptors: what 'apt install' leaves behind. */
    private void hookBinaries(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("bin"));
        for (final String name : List.of("sokar-hook-nft", "sokar-hook-supervisor",
                "sokar-hook-reader")) {
            final Path binary = dir.resolve("bin").resolve(name);
            Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true);
        }
    }

    /**
     * Runs the CLI, naming the repository for a task start that did not name one itself.
     * <p>
     * Every project here is the one-repository project called {@code uc}, and starting a task
     * always names a repository - there is no default, not even then. Added by the helper rather
     * than by thirty call sites, each of which is about something else; the requirement itself is
     * measured by {@link #startingATaskWithoutNamingARepositoryIsRefused}.
     */
    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final List<String> all = new java.util.ArrayList<>(List.of(args));
        if (all.size() > 1 && "task".equals(all.get(0)) && "start".equals(all.get(1))
                && !all.contains("--repository") && !all.contains("-r")) {
            all.add("--repository");
            all.add("uc");
        }
        // Named 'shell' unless a test names it: an unnamed start takes its repository's name, which is measured on
        // its own below, and every other test here is about something else.
        if (all.size() > 1 && "task".equals(all.get(0)) && "start".equals(all.get(1))
                && (all.size() == 2 || all.get(2).startsWith("-"))) {
            all.add(2, "shell");
        }
        return cmd.execute(all.toArray(new String[0]));
    }

    @Test
    void startingATaskWithoutNamingARepositoryIsRefused(@TempDir Path dir) throws IOException {

        final CommandLine cmd = SokarCli.commandLine(context(dir, true));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        // No --repository, and the project has exactly one. Sokar still does not pick: a project
        // that grew a second repository would otherwise silently change what this command does.
        final int code = cmd.execute("task", "start", "--attach", "shell", "--detach",
                "-p", projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(64);
        // And the refusal is one somebody can act on without opening the file.
        assertThat(err.toString()).contains("--repository").contains("uc");
        // Unnamed, the task has no name yet: a hint that put one in suggested 'shell', a name nobody chose.
        assertThat(err.toString()).doesNotContain("task start shell");
    }

    @Test
    void anUnnamedStartIsNamedAfterItsRepositoryAndSaysSo(@TempDir Path dir) throws IOException {

        final CommandLine cmd = SokarCli.commandLine(context(dir, true));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        cmd.execute("task", "start", "--attach", "shell", "--detach", "-p", projectFile(dir, MINIMAL),
                "--repository", "uc");

        assertThat(out.toString()).contains("task           uc - named after its repository");
        assertThat(String.join("\n", runner.lines())).contains("sokar-uc-uc").doesNotContain("sokar-uc-shell");
    }

    @Test
    void aDryRunNeedsNoRepositoryBecauseItStartsNothing(@TempDir Path dir) throws IOException {

        // The refusal protects against starting a task on a guessed repository; a dry run starts
        // nothing. What it reports without one is the project-level plan - the project's own
        // block, which is true of every repository because a repository only ever ADDS to it.
        final CommandLine cmd = SokarCli.commandLine(context(dir, true));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        final int code = cmd.execute("task", "start",
                "-p", projectFile(dir, TWO_REPOSITORIES), "--dry-run");

        assertThat(code).isZero();
        // And it says which plan this is, or a project-level answer reads as a task's.
        assertThat(out.toString()).contains("every repository");
    }

    @Test
    void namingARepositoryTheProjectDoesNotHaveIsRefusedByName(@TempDir Path dir)
            throws IOException {

        final int code = execute(context(dir, true), "task", "start",
                "-p", projectFile(dir, MINIMAL), "--repository", "nowhere",
                "--dry-run");

        assertThat(code).isEqualTo(64);
        assertThat(err.toString()).contains("nowhere").contains("uc");
    }

    /**
     * Writes a project file and records where it is, then answers its NAME.
     *
     * <p>Commands take a project name now, and a name resolves through what this machine knows:
     * the verified clone of a followed project, then where a task last read one. A fixture that
     * only wrote a file into a temporary directory would be a project this machine has never
     * heard of - which is exactly what the refusal is for, and not what these tests are about.
     */
    private String projectFile(Path dir, String content) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, content);
        // Read out of the text rather than through the reader: one of these fixtures is a file
        // the reader refuses on purpose, and it still has to be a project this machine can name.
        final java.util.regex.Matcher named = java.util.regex.Pattern
                .compile("(?m)^\\s+name:\\s*\"?([a-z0-9-]+)\"?").matcher(content);
        final String name = named.find() ? named.group(1) : "uc";
        Files.createDirectories(dir.resolve("data/sokar/projects"));
        Files.writeString(dir.resolve("data/sokar/projects").resolve(name),
                file.toAbsolutePath() + "\n");
        return name;
    }

    @Test
    void reportsTheResolvedProject(@TempDir Path dir) throws IOException {

        final int code = execute(context(dir, true), "task", "start",
                "-p", projectFile(dir, MINIMAL), "--dry-run");

        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("project        uc")
                .contains("security class guarded")
                .contains("task image     sokar/uc");
        assertThat(runner.invocations()).as("a dry run must touch nothing").isEmpty();
    }

    @Test
    void refusesToRunWhenTheHookBinariesAreNotInstalled(@TempDir Path dir) throws IOException {

        // A container started without its firewall looks completely normal, which is why this
        // must stop rather than warn. Writing descriptors would not help: they would name
        // binaries that are not there, which is a broken installation rather than a missing step.
        final int code = execute(context(dir, false), "task", "start",
                "-p", projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("binaries that are not installed");
        // Asking podman its version comes first and reads nothing else; what must not happen is
        // a container existing without the hooks that give it a firewall.
        assertThat(runner.lines()).allMatch(line -> line.startsWith("podman version"));
    }

    @Test
    void registersTheHooksItselfRatherThanAskingForACommand(@TempDir Path dir) throws IOException {

        // Being told to run 'sokar setup' is a thing people forget, which is how a machine ends
        // up with an installation nobody completed. The binaries are there and the descriptors
        // are not, which is the state after 'apt install' - the package deliberately does not
        // write them, because podman reads them per user and root does not know whose.
        hookBinaries(dir);

        final int code = execute(context(dir, false), "task", "start", "--attach", "shell",
                "--detach", "-p", projectFile(dir, MINIMAL));

        assertThat(code).isZero();
        // Said out loud: this writes into the operator's own podman configuration.
        assertThat(out.toString()).contains("hooks").contains("registered with podman");
        assertThat(dir.resolve("config/containers/oci/hooks.d/sokar-hook-nft-createRuntime.json"))
                .exists();
    }

    @Test
    void bringsDescriptorsAnUpgradeLeftBehindUpToDate(@TempDir Path dir) throws IOException {

        // The other repairable state. A package replaces the binaries and never touches these
        // files, so a release that changes what a descriptor says leaves the old one in place.
        final SokarContext context = context(dir, true);
        final Path descriptor = dir.resolve(
                "config/containers/oci/hooks.d/sokar-hook-nft-poststop.json");
        Files.writeString(descriptor, "{\"version\":\"1.0.0\",\"hook\":{}}");

        final int code = execute(context, "task", "start", "--attach", "shell", "--detach",
                "-p", projectFile(dir, MINIMAL));

        assertThat(code).isZero();
        assertThat(out.toString()).contains("brought up to date");
        assertThat(Files.readString(descriptor)).contains("sokar-hook-nft");
    }

    @Test
    void writesThePolicyBeforeCreatingTheContainer(@TempDir Path dir) throws IOException {

        // The nft hook reads both files while the container is being created. Writing them
        // afterwards would leave a window with a container and no firewall.
        execute(context(dir, true), "task", "start", "--attach", "shell", "-p", projectFile(dir, MINIMAL));

        final Path state = root.resolve("run/sokar").resolve(containerName());
        assertThat(state.resolve("ruleset.nft")).exists();
        assertThat(state.resolve("sidecar.json")).exists();
        assertThat(Files.readString(state.resolve("ruleset.nft"))).contains("policy drop;");
    }

    /** The host key a person confirmed: without it a start over ssh stops before anything is created. */
    private static void trustGithub(final SokarContext context) throws IOException {
        final Path known = FollowCredential.knownHostsFile(context);
        Files.createDirectories(known.getParent());
        Files.writeString(known, "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample\n");
    }

    @Test
    void anOnlineTaskReachesItsSshUpstreamOnTheSshPortAndNothingElseThere(@TempDir Path dir) throws IOException {

        // Its remote IS the upstream, over ssh: with only 80 and 443 the fetch hung for ever, dropped without a word.
        final SokarContext context = context(dir, true);
        trustGithub(context);
        execute(context, "task", "start", "--attach", "shell", "-p", projectFile(dir, """
                project:
                  name: "uc"
                  security_class: "online"
                  upstream: "git@github.com:example/uc.git"
                image:
                  base_image: "ubuntu:24.04"
                """));

        final Path state = root.resolve("run/sokar").resolve(containerName());
        assertThat(state.resolve("ruleset.nft")).as("the start said:%n%s%n%s", out, err).exists();
        assertThat(Files.readString(state.resolve("ruleset.nft")))
                .contains("ip daddr @upstream_v4 tcp dport 22 accept")
                .contains("ip6 daddr @upstream_v6 tcp dport 22 accept");
        assertThat(Files.readString(state.resolve("dns.conf")))
                .as("the upstream's addresses reach the upstream set as they are answered")
                .contains("nftset=/github.com/inet#sokar#allowed_v4,inet#sokar#allowed_v6,inet#sokar#upstream_v4,"
                        + "inet#sokar#upstream_v6");
    }

    @Test
    void aGuardedTaskReachesNoUpstreamOverSshSinceItsWorkLeavesThroughTheGate(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir, true);
        trustGithub(context);
        execute(context, "task", "start", "--attach", "shell", "-p", projectFile(dir, """
                project:
                  name: "uc"
                  security_class: "guarded"
                  upstream: "git@github.com:example/uc.git"
                image:
                  base_image: "ubuntu:24.04"
                """));

        final Path state = root.resolve("run/sokar").resolve(containerName());
        assertThat(Files.readString(state.resolve("ruleset.nft"))).doesNotContain("upstream_v4")
                .doesNotContain("dport 22");
    }

    @Test
    void annotatesTheContainerSoTheHooksFire(@TempDir Path dir) throws IOException {

        execute(context(dir, true), "task", "start", "--attach", "shell", "-p", projectFile(dir, MINIMAL));

        assertThat(runner.only("create").describe())
                .contains("--annotation org.fuin.sokar.sidecar=")
                .contains("--security-opt no-new-privileges");
    }

    @Test
    void handsTheTerminalToTheShell(@TempDir Path dir) throws IOException {

        execute(context(dir, true), "task", "start", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL), "--shell", "/bin/sh");

        // Through the task's ONE session, not beside it. A plain exec ran the agent as a child
        // of this terminal: closing the window took it with it, and attaching from anywhere else
        // created a second, empty session and showed a bare shell in the workspace.
        assertThat(execCalls).hasSize(1);
        assertThat(execCalls.getFirst()).startsWith("podman", "exec", "--interactive", "--tty",
                containerName(), "tmux", "-f", "/etc/sokar/tmux.conf",
                "new-session", "-A", "-s", "sokar");
        // One argument, because tmux joins several into one string with spaces and would take
        // the script apart at its quoting.
        // The shell is a child, not an exec: the marker that says the work ended is written
        // after it returns, and 'exec' would leave nothing to write it.
        assertThat(execCalls.getFirst().getLast())
                .contains("/bin/sh -l; : > ").doesNotContain("exec /bin/sh");
    }

    @Test
    void tellsTheContainerWhichTerminalTheOperatorIsAt(@TempDir Path dir) throws IOException {

        // End to end through the wiring, because the parts were right and the thread between them
        // was not: the context builds the runner, the runner builds podman, and each of them used
        // to fall back to this process's own environment. What that cost was not production - it
        // was a test that read the terminal of whoever ran it and asserted something different in
        // CI, which is the same class of defect as the one this feature fixes.
        final SokarContext context = context(dir, true,
                name -> "TERM".equals(name) ? "xterm-256color" : null);
        runner.answering("podman exec " + containerName() + " infocmp xterm-256color", "");

        execute(context, "task", "start", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL), "--shell", "/bin/sh");

        assertThat(execCalls.getFirst())
                .containsSubsequence("--env", "TERM=xterm-256color", containerName());
    }

    @Test
    void aTestContextReadsNoEnvironmentAtAll() {

        // The seam that keeps the test above honest. A context built the three-argument way -
        // which is how every test builds one - must read nothing from this process, or a suite
        // passes on a developer's machine and asserts something else where there is no terminal.
        assertThat(new SokarContext(runner, SokarPaths.current(), arguments -> 0)
                .environment().apply("TERM")).isNull();
    }

    @Test
    void askingForTheAgentsSessionWithNoAgentIsRefusedBeforeAnythingIsCreated(@TempDir Path dir)
            throws IOException {

        // It used to attach a plain shell and record the mode as AGENT, so a task said it was
        // something it was not - and the person who asked for an agent got a bare prompt with
        // nothing explaining why. CanStart already answered NO_AGENT for this, so the check and
        // the launch disagreed about the same machine.
        final int code = execute(context(dir, true), "task", "start",
                "-p", projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("no agent to attach");
        assertThat(execCalls).as("refused before a container exists to attach to").isEmpty();
    }

    @Test
    void aShellTaskNeedsNoAgentAtAll(@TempDir Path dir) throws IOException {

        // Working inside the container by hand is exactly what a shell task is for, so refusing
        // it for want of an agent would take away the case the refusal above points people at.
        final int code = execute(context(dir, true), "task", "start", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(code).isZero();
        assertThat(execCalls).hasSize(1);
    }

    @Test
    void aPromptWithNoAgentIsRefusedRatherThanReportedAsDone(@TempDir Path dir) throws IOException {

        // The difference between "started" and "ran". A prompt asks for work to be performed, and
        // a run that answers success without an agent having done anything leaves somebody waiting
        // for output that was never going to come.
        final int code = execute(context(dir, true), "task", "start",
                "-p", projectFile(dir, MINIMAL), "-P", "fix the parser");

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("a prompt needs an agent");
    }

    @Test
    void removesAContainerThatWasNeverCreated(@TempDir Path dir) throws IOException {

        // Nothing exists to hold, so the tidy-up runs as it always did.
        runner.failing("start", 125, "hook failed");

        final int code = execute(context(dir, true), "task", "start", "--rm", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(70);
        assertThat(err.toString()).contains("hook failed");
        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        assertThat(execCalls).as("no shell is attached to a container that never started").isEmpty();
    }

    @Test
    void holdsAFailedTaskInsteadOfRemovingIt(@TempDir Path dir) throws IOException {

        // --keep has to be decided before the run, and the run worth looking at is the one that
        // went wrong, which is known only afterwards. So a failure is held rather than swept up.
        // Built BEFORE the broad answers below: the fake takes the first key
        // that matches, and 'exec' would otherwise swallow the session check
        // the fixture registers - reading a container with no session as one
        // somebody had detached from, so nothing would ever be torn down.
        final SokarContext prepared = context(dir, true);
        runner.answering("container inspect", "c0ffee\n");
        runner.failing("start", 125, "hook failed");

        final int code = execute(prepared, "task", "start", "--rm", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(70);
        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
        // The advice has to name commands the CLI still has. It named 'task resume' and
        // 'task stop --purge' - both removed at the lifecycle cut - so somebody following a
        // failure report was sent to two commands that refuse.
        assertThat(out.toString()).contains("kept").contains("it failed, so nothing was removed")
                .contains("task attach").contains("task remove")
                .doesNotContain("task resume").doesNotContain("--purge");
        // Held, not abandoned: a task nobody is watching that still holds a firewall, a gate and a
        // credential proxy is not kept.
        assertThat(runner.lines()).anyMatch(line -> line.contains("podman stop"));
    }

    @Test
    void keepsTheContainerWhenAskedTo(@TempDir Path dir) throws IOException {

        runner.failing("start", 125, "hook failed");

        execute(context(dir, true), "task", "start",
                "-p", projectFile(dir, MINIMAL));

        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
    }

    @Test
    void failsWithoutASubcommand(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true))).isEqualTo(2);
        assertThat(err.toString()).contains("Usage: sokar");
    }

    @Test
    void failsOnAProjectThisMachineDoesNotHave(@TempDir Path dir) throws IOException {

        // A name, not a path - so the answer is no longer "no file there" but "this machine has no
        // such project", which is the question a person actually asked. It names what there is,
        // because which projects a machine has is something only the machine can answer.
        projectFile(dir, MINIMAL);

        assertThat(execute(context(dir, true), "task", "start", "-p", "nowhere", "--dry-run"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("no project 'nowhere'").contains("uc");
    }

    @Test
    void failsOnAnUnreadableProjectFile(@TempDir Path dir) throws IOException {

        final String name = projectFile(dir, "project:\n  name: uc\n");

        assertThat(execute(context(dir, true), "task", "start", "-p", name, "--dry-run"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("no 'image' section");
    }

    @Test
    void rejectsAnUnknownOption(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true), "task", "start",
                "-p", projectFile(dir, MINIMAL), "--nope")).isEqualTo(2);
    }

    @Test
    void printsTheTwoLinesOtherRepositoriesReadFromIt(@TempDir Path dir) throws IOException {

        // These two lines are a contract, not decoration. The three agent repositories parse them
        // out of a start: all three take 'container' to know what to act on, and sokar-omp's
        // broker-check.sh takes 'sidecar' to find the state directory. Nothing guarded them, so a
        // tidy-up of this output would have gone out green here and turned six acceptance legs red
        // somewhere else. Asked for by name on the agent channel on 2026-09-11.
        execute(context(dir, true), "task", "start", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(out.toString())
                .containsPattern("(?m)^container " + containerName() + "$")
                .containsPattern("(?m)^sidecar   \\S+sidecar\\.json$");
    }

    private String containerName() {
        // One container per project and task, with nothing unique appended. It used to carry the
        // launching process's pid, which is why a task could never be found again by its name.
        return "sokar-uc-shell";
    }

    @Test
    void stopsTheHelpersWhenTheContainerNeverStarts(@TempDir Path dir) throws Exception {

        // A container that ran is reaped by the poststop hook. One that never started fires no
        // hook, and the vault proxy, gate and watcher launched before it then outlive the run:
        // measured on both test machines, where each failed run stranded three processes holding
        // their sockets, and the next run failed for a reason unrelated to what changed.
        final SokarContext context = context(dir, true);
        runner.failing("start", 125, "OCI runtime error");

        final Path state = dir.resolve("run/sokar").resolve(containerName());
        Files.createDirectories(state);
        final Process helper = new ProcessBuilder("sleep", "120").start();
        // Id and start time, as a helper writes them: a bare id is refused, because by the time
        // anything reads a leftover file that number may belong to something else entirely.
        org.fuin.sokar.wire.HelperPid.record(state.resolve("vault.pid"), helper.toHandle());

        execute(context, "task", "start", "--attach", "shell", "-p", projectFile(dir, MINIMAL), "--detach");

        assertThat(helper.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("the helper must be stopped, not left holding its socket").isTrue();
        assertThat(state.resolve("vault.pid")).doesNotExist();
    }

    @Test
    void leavesTheHelpersAloneWhileTheContainerRuns(@TempDir Path dir) throws Exception {

        // The mirror of the above: a running container owns its helpers, and reaping them here
        // would break every task that is working normally.
        final SokarContext context = context(dir, true);
        runner.answering("container inspect", "4711");

        final Path state = dir.resolve("run/sokar").resolve(containerName());
        Files.createDirectories(state);
        Files.writeString(state.resolve("vault.pid"), "4711");

        execute(context, "task", "start", "-p", projectFile(dir, MINIMAL),
                "--detach");

        assertThat(state.resolve("vault.pid")).exists();
    }

    @Test
    void removesTheContainerWhenTheShellExits(@TempDir Path dir) throws IOException {

        // The bug this exists for: attaching used to replace this process, so nothing was left to
        // remove the container. Every interactive task leaked one while printing the opposite.
        execute(context(dir, true), "task", "start", "--rm", "-p", projectFile(dir, MINIMAL));

        assertThat(runner.invocations()).anySatisfy(command ->
                assertThat(command.describe()).contains("rm"));
    }

    @Test
    void removesTheContainerWhenTheShellsLastCommandFailed(@TempDir Path dir) throws IOException {

        // A shell exits with its last command's status, which says nothing about the task.
        // Reported from a machine where a typo at the prompt - 'bash: /exit: No such file or
        // directory' - made leaving the session print "it failed, so nothing was removed" and
        // keep the container, its firewall and its resolver running.
        execExit = 127;
        // Without this the container does not exist as far as the fake runtime is concerned, the
        // keep branch is unreachable, and the test would pass however the verdict is computed.
        runner.answering("container inspect", "c0ffee\n");

        final int code = execute(context(dir, true), "task", "start", "--rm", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(out.toString()).doesNotContain("it failed");
        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        // The code still reaches the caller, the way ssh reports a remote command's status.
        assertThat(code).isEqualTo(127);
    }

    @Test
    void doesNotDiscardWorkThatNeverReachedTheGate(@TempDir Path dir) throws IOException {

        // A workspace is in the container's own writable layer, so removing the container
        // destroys it. 'task stop --purge' refuses to do that and offers --rescue; walking out
        // of a shell did it without asking, which is the same destruction by a different route.
        // idOf and pidOf both run 'container inspect' and the fake takes the first match in
        // insertion order, so the more specific key goes first - otherwise the pid query answers
        // 'c0ffee', parses as no pid, and the work check never runs.
        // Built BEFORE the broad answers below: the fake takes the first key
        // that matches, and 'exec' would otherwise swallow the session check
        // the fixture registers - reading a container with no session as one
        // somebody had detached from, so nothing would ever be torn down.
        final SokarContext prepared = context(dir, true);
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        // What the container answers when asked: one changed file, two commits nobody pushed.
        runner.answering("exec", "1 2\n");

        execute(prepared, "task", "start", "--rm", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(runner.lines()).as("work that exists nowhere else must not be removed")
                .noneMatch(line -> line.contains("rm --force"));
        assertThat(out.toString()).contains("kept").contains("never reached the gate")
                .contains("--rescue");
    }

    @Test
    void removesATaskThatHandedEverythingBack(@TempDir Path dir) throws IOException {

        // The negative case, and the common one: nothing uncommitted and nothing unpushed, so
        // there is nothing to protect and the container goes. Keeping it would bring back the
        // pile of dead containers this is meant to avoid.
        // Built BEFORE the broad answers below: the fake takes the first key
        // that matches, and 'exec' would otherwise swallow the session check
        // the fixture registers - reading a container with no session as one
        // somebody had detached from, so nothing would ever be torn down.
        final SokarContext prepared = context(dir, true);
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        runner.answering("exec", "0 0\n");

        execute(prepared, "task", "start", "--rm", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        assertThat(out.toString()).doesNotContain("never reached the gate");
    }

    @Test
    void keepsATaskWhoseSessionNeverFinished(@TempDir Path dir) throws IOException {

        // The other half of the same decision. Somebody who walked out of a shell ended the task;
        // somebody whose terminal died did not, and the workspace may hold commits that never
        // reached the gate. So this one is stopped and held rather than removed.
        execDies = true;
        runner.answering("container inspect", "c0ffee\n");

        execute(context(dir, true), "task", "start", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(runner.lines()).as("an interrupted run must not be swept up")
                .noneMatch(line -> line.contains("rm --force"));
    }

    @Test
    void labelsTheContainerWithItsProjectAndClass(@TempDir Path dir) throws IOException {

        // So a listing can still say what a task belongs to after a reboot. The sidecar holding
        // the same facts is in $XDG_RUNTIME_DIR and does not survive one.
        execute(context(dir, true), "task", "start", "--attach", "shell", "--detach",
                "-p", projectFile(dir, MINIMAL));

        assertThat(runner.lines()).anyMatch(line -> line.contains("create")
                && line.contains("--label org.fuin.sokar.project=uc")
                && line.contains("--label org.fuin.sokar.class=guarded"));
    }

    /**
     * A project with a second repository, so a label can be wrong in a visible way.
     * <p>
     * <strong>No upstream on it, deliberately.</strong> The gate is built with a real command
     * runner even here, so a repository that named one would have this test cloning from that
     * address - and an unreachable host does not fail, it waits. Measured: the run hung until it
     * was killed. A repository with no upstream is a legitimate one whose work stays on this
     * machine, and it exercises the label exactly as well.
     */
    private static final String TWO_REPOSITORIES = MINIMAL + """
            repositories:
              backend:
            """;

    @Test
    void labelsTheContainerWithTheRepositoryTheTaskWorksOn(@TempDir Path dir) throws IOException {

        // A task works on exactly one repository and that is fixed when it is created, so a
        // listing has to be able to say which one after a reboot - and bringing a stopped task
        // back has to carry it over. The sidecar cannot answer: it predates repositories.
        execute(context(dir, true), "task", "start", "--attach", "shell", "--detach",
                "-p", projectFile(dir, TWO_REPOSITORIES), "--repository", "backend");

        assertThat(runner.lines()).anyMatch(line -> line.contains("create")
                && line.contains("--label org.fuin.sokar.repository=backend"));
    }

    @Test
    void aTaskIsToldTheIssuePrefixesItsRepositoryOwns(@TempDir Path dir) throws IOException {

        // So an agent cites and names its repository's issues the way the repository does, without a list of its own.
        execute(context(dir, true), "task", "start", "--attach", "shell", "--detach",
                "-p", projectFile(dir, MINIMAL + """
                        repositories:
                          backend:
                            issues: ["B", "P"]
                        """), "--repository", "backend");

        assertThat(runner.only("create").environment()).as("the value, in the environment podman copies it from")
                .containsEntry(TaskLaunch.ISSUE_PREFIXES, "B,P");
        assertThat(runner.lines()).as("the name alone on the command line").anyMatch(line -> line.contains("create")
                && line.contains("--env " + TaskLaunch.ISSUE_PREFIXES + " "));
    }

    @Test
    void aTaskOnARepositoryWithoutIssuesIsToldNone(@TempDir Path dir) throws IOException {
        execute(context(dir, true), "task", "start", "--attach", "shell", "--detach",
                "-p", projectFile(dir, TWO_REPOSITORIES), "--repository", "backend");

        assertThat(runner.only("create").environment()).containsEntry(TaskLaunch.ISSUE_PREFIXES, "");
    }

    @Test
    void refusesTheOwnRepositoryOfAProjectThatNamesOthers(@TempDir Path dir) throws IOException {

        // It holds project.yml, which decides what every task of the project may reach.
        final CommandLine cmd = SokarCli.commandLine(context(dir, true));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        final int code = cmd.execute("task", "start", "--attach", "shell", "--detach",
                "-p", projectFile(dir, TWO_REPOSITORIES), "--repository", "uc");

        assertThat(code).isNotZero();
        assertThat(err.toString()).contains("'uc' is the project's own repository").contains("works in:");
        assertThat(runner.lines()).noneMatch(line -> line.contains(" create "));
    }

    @Test
    void leavesTheContainerAloneWithKeep(@TempDir Path dir) throws IOException {

        // The negative case: --keep has to mean something, and today it did not.
        execute(context(dir, true), "task", "start", 
                "-p", projectFile(dir, MINIMAL));

        // 'rm --force', which is how a task container is removed - not any argument containing
        // "rm". A throwaway container elsewhere in the run legitimately passes --rm, and matching
        // that made this fail for the wrong reason.
        assertThat(runner.invocations()).noneSatisfy(command ->
                assertThat(command.describe()).contains("rm --force"));
    }

    @Test
    void attachesAPlainShellWhenAsked(@TempDir Path dir) throws IOException {

        // The negative case: --attach shell must not wrap anything around the shell.
        execute(context(dir, true), "task", "start", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        // No agent runs, so there is no full-screen interface to clean up after.
        assertThat(String.join(" ", execCalls.getLast())).doesNotContain("stty sane");
    }

    @Test
    void noticesTheVaultCopyHasFallenBehind() {

        // An agent that refreshes its own token leaves the imported copy behind, and the task
        // then fails as an authentication error with no hint that a refresh would fix it.
        assertThat(CredentialChoice.staleCredential(
                new org.fuin.sokar.vault.VaultEntry("old-token", "oauth"),
                org.fuin.sokar.agent.api.Credential.of("oauth", "new-token"))).isTrue();
    }

    @Test
    void saysNothingWhenTheCopyIsCurrentOrDeliberatelyDifferent() {

        // Three negatives, and the middle one matters most: storing a different kind on purpose
        // is not staleness, and warning about it every run would teach the operator to ignore it.
        assertThat(CredentialChoice.staleCredential(
                new org.fuin.sokar.vault.VaultEntry("same", "oauth"),
                org.fuin.sokar.agent.api.Credential.of("oauth", "same"))).isFalse();
        assertThat(CredentialChoice.staleCredential(
                new org.fuin.sokar.vault.VaultEntry("a-key", "api-key"),
                org.fuin.sokar.agent.api.Credential.of("oauth", "a-token"))).isFalse();
        assertThat(CredentialChoice.staleCredential(null,
                org.fuin.sokar.agent.api.Credential.of("oauth", "a-token"))).isFalse();
        assertThat(CredentialChoice.staleCredential(
                new org.fuin.sokar.vault.VaultEntry("a-key", "api-key"), null)).isFalse();
    }

    @Test
    void detachingLeavesTheTaskRunningAndTearsDownNothing(@TempDir Path dir) throws IOException {

        // The dangerous half of running in a session. Leaving the window ends the command this
        // terminal was attached to, exactly as finishing does - and until the session could be
        // asked, both read as "the work is over". Tearing down on a detach would stop the gate,
        // the credential broker and the clearance watcher under a running agent, which is the
        // state the contract warns about: a container that is up with no helpers.
        final SokarContext prepared = context(dir, true);
        // No marker: the shell never returned, so nobody finished.
        runner.failing("test -f", 1, "");

        execute(prepared, "task", "start", "--rm", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(runner.lines())
                .as("the container stays, --rm or not: it is still running")
                .noneMatch(line -> line.contains("rm --force"));
        assertThat(runner.lines())
                .as("and nothing stops it either")
                .noneMatch(line -> line.contains("podman stop"));
        assertThat(out.toString()).contains("detached").contains("still running")
                .contains("task attach");
    }

    @Test
    void aMarkerFromTheWindowsShellIsTheWorkBeingOver(@TempDir Path dir) throws IOException {

        // The other half: the window's shell returned and wrote the marker, which is the only
        // unambiguous statement that the work is over.
        final SokarContext prepared = context(dir, true);
        runner.answering("test -f", "");
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        runner.answering("exec", "0 0\n");

        execute(prepared, "task", "start", "--rm", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL));

        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        assertThat(out.toString()).doesNotContain("detached");
    }

    @Test
    void aFollowedProjectsVerifiedFileWinsOverTheOneInTheDirectory(@TempDir Path dir)
            throws IOException {

        // The measurement that matters: start a task from a directory holding a DIFFERENT
        // project.yml and show that the directory's file had no effect. Following checks a
        // signature against a key pinned out of band, and reading anything else afterwards throws
        // that check away.
        final SokarContext context = context(dir, true);
        new FollowedProjects(context.paths().projects().followed()).write(
                new FollowedProjects.Followed("uc", "git@example.com:x/uc.git",
                        "a1b2c3d4", "2026-09-19T00:00:00Z", "APPLIED", ""));
        final Path clone = context.paths().projects().followedClone("uc");
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("project.yml"), MINIMAL.replace(
                "base_image: \"ubuntu:24.04\"", "base_image: \"ubuntu:22.04\""));

        // The directory says 24.04; the verified clone says 22.04.
        execute(context, "task", "start", "-p", projectFile(dir, MINIMAL),
                "--dry-run");

        assertThat(out.toString()).contains("base image     ubuntu:22.04");
        // And it says where that came from, so nobody wonders why the file they are looking at is
        // not the one being used.
        assertThat(out.toString()).contains("a1b2c3d4").contains("followed, verified");
    }

    @Test
    void aFollowedProjectWithNothingInForceRefusesRatherThanUsingTheLocalFile(@TempDir Path dir)
            throws IOException {

        final SokarContext context = context(dir, true);
        // Followed and refused - no clone was written.
        new FollowedProjects(context.paths().projects().followed()).write(
                new FollowedProjects.Followed("uc", "git@example.com:x/uc.git",
                        "", "2026-09-19T00:00:00Z", "UNKNOWN_KEY", "a key that is not pinned"));

        final int code = execute(context, "task", "start",
                "-p", projectFile(dir, MINIMAL), "--dry-run");

        // Falling back would run the very thing the machine declined to apply, and would make a
        // refusal look as though it had no effect.
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("nothing of it is in force");
    }

    @Test
    void labelsTheContainerWithTheCommitItsConfigurationWasVerifiedAt(@TempDir Path dir)
            throws IOException {

        final SokarContext context = context(dir, true);
        new FollowedProjects(context.paths().projects().followed()).write(
                new FollowedProjects.Followed("uc", "git@example.com:x/uc.git",
                        "a1b2c3d4", "2026-09-19T00:00:00Z", "APPLIED", ""));
        final Path clone = context.paths().projects().followedClone("uc");
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("project.yml"), MINIMAL);

        execute(context, "task", "start", "--attach", "shell", "--detach",
                "-p", projectFile(dir, MINIMAL));

        // The project moves on; this must not. "What was this task running under" is asked after
        // something has gone wrong, by which time the clone holds something newer.
        assertThat(runner.lines()).anyMatch(line -> line.contains("create")
                && line.contains("--label org.fuin.sokar.commit=a1b2c3d4"));
    }
}
