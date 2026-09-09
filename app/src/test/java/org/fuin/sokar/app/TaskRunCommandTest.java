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

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final List<List<String>> execCalls = new ArrayList<>();

    private Path root;

    private SokarContext context(Path dir, boolean hooksInstalled) throws IOException {
        root = dir;
        // A machine Sokar will run on. Without this the fake answers nothing to 'podman version',
        // which is indistinguishable from a podman too old to support - and every run refuses.
        runner.answering("podman version", "5.8.1");
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
        });
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = new CommandLine(new SokarCli(), new SokarFactory(context));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private Path projectFile(Path dir, String content) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void reportsTheResolvedProject(@TempDir Path dir) throws IOException {

        final int code = execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString(), "--dry-run");

        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("project        uc")
                .contains("security class guarded")
                .contains("task image     sokar/uc");
        assertThat(runner.invocations()).as("a dry run must touch nothing").isEmpty();
    }

    @Test
    void refusesToRunWithoutTheHooks(@TempDir Path dir) throws IOException {

        // A container started without its firewall looks completely normal, which is why this
        // must stop rather than warn.
        final int code = execute(context(dir, false), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("run 'sokar setup' first");
        // Asking podman its version comes first and reads nothing else; what must not happen is
        // a container existing without the hooks that give it a firewall.
        assertThat(runner.lines()).allMatch(line -> line.startsWith("podman version"));
    }

    @Test
    void writesThePolicyBeforeCreatingTheContainer(@TempDir Path dir) throws IOException {

        // The nft hook reads both files while the container is being created. Writing them
        // afterwards would leave a window with a container and no firewall.
        execute(context(dir, true), "task", "run", "--attach", "shell", "-p", projectFile(dir, MINIMAL).toString());

        final Path state = root.resolve("run/sokar").resolve(containerName());
        assertThat(state.resolve("ruleset.nft")).exists();
        assertThat(state.resolve("sidecar.json")).exists();
        assertThat(Files.readString(state.resolve("ruleset.nft"))).contains("policy drop;");
    }

    @Test
    void annotatesTheContainerSoTheHooksFire(@TempDir Path dir) throws IOException {

        execute(context(dir, true), "task", "run", "--attach", "shell", "-p", projectFile(dir, MINIMAL).toString());

        assertThat(runner.only("create").describe())
                .contains("--annotation org.fuin.sokar.sidecar=")
                .contains("--security-opt no-new-privileges");
    }

    @Test
    void handsTheTerminalToTheShell(@TempDir Path dir) throws IOException {

        execute(context(dir, true), "task", "run", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL).toString(), "--shell", "/bin/sh");

        assertThat(execCalls).hasSize(1);
        assertThat(execCalls.getFirst()).startsWith("podman", "exec", "--interactive", "--tty",
                containerName(), "/bin/sh");
        assertThat(String.join(" ", execCalls.getFirst())).contains("exec /bin/sh -l");
    }

    @Test
    void askingForTheAgentsSessionWithNoAgentIsRefusedBeforeAnythingIsCreated(@TempDir Path dir)
            throws IOException {

        // It used to attach a plain shell and record the mode as AGENT, so a task said it was
        // something it was not - and the person who asked for an agent got a bare prompt with
        // nothing explaining why. CanStart already answered NO_AGENT for this, so the check and
        // the launch disagreed about the same machine.
        final int code = execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("no agent to attach");
        assertThat(execCalls).as("refused before a container exists to attach to").isEmpty();
    }

    @Test
    void aShellTaskNeedsNoAgentAtAll(@TempDir Path dir) throws IOException {

        // Working inside the container by hand is exactly what a shell task is for, so refusing
        // it for want of an agent would take away the case the refusal above points people at.
        final int code = execute(context(dir, true), "task", "run", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(code).isZero();
        assertThat(execCalls).hasSize(1);
    }

    @Test
    void aPromptWithNoAgentIsRefusedRatherThanReportedAsDone(@TempDir Path dir) throws IOException {

        // The difference between "started" and "ran". A prompt asks for work to be performed, and
        // a run that answers success without an agent having done anything leaves somebody waiting
        // for output that was never going to come.
        final int code = execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString(), "-P", "fix the parser");

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("a prompt needs an agent");
    }

    @Test
    void removesAContainerThatWasNeverCreated(@TempDir Path dir) throws IOException {

        // Nothing exists to hold, so the tidy-up runs as it always did.
        runner.failing("start", 125, "hook failed");

        final int code = execute(context(dir, true), "task", "run", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(code).isEqualTo(70);
        assertThat(err.toString()).contains("hook failed");
        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        assertThat(execCalls).as("no shell is attached to a container that never started").isEmpty();
    }

    @Test
    void holdsAFailedTaskInsteadOfRemovingIt(@TempDir Path dir) throws IOException {

        // --keep has to be decided before the run, and the run worth looking at is the one that
        // went wrong, which is known only afterwards. So a failure is held rather than swept up.
        runner.answering("container inspect", "c0ffee\n");
        runner.failing("start", 125, "hook failed");

        final int code = execute(context(dir, true), "task", "run", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(code).isEqualTo(70);
        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
        assertThat(out.toString()).contains("kept").contains("it failed, so nothing was removed")
                .contains("task resume").contains("--purge");
        // Held, not abandoned: a task nobody is watching that still holds a firewall, a gate and a
        // credential proxy is not kept.
        assertThat(runner.lines()).anyMatch(line -> line.contains("podman stop"));
    }

    @Test
    void keepsTheContainerWhenAskedTo(@TempDir Path dir) throws IOException {

        runner.failing("start", 125, "hook failed");

        execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString(), "--keep");

        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
    }

    @Test
    void failsWithoutASubcommand(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true))).isEqualTo(2);
        assertThat(err.toString()).contains("Usage: sokar");
    }

    @Test
    void failsOnAMissingProjectFile(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true), "task", "run", "-p", "/does/not/exist.yml", "--dry-run"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("No project file at /does/not/exist.yml");
    }

    @Test
    void failsOnAnUnreadableProjectFile(@TempDir Path dir) throws IOException {

        final Path file = projectFile(dir, "project:\n  name: uc\n");

        assertThat(execute(context(dir, true), "task", "run", "-p", file.toString(), "--dry-run"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("no 'image' section");
    }

    @Test
    void rejectsAnUnknownOption(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true), "task", "run",
                "-p", projectFile(dir, MINIMAL).toString(), "--nope")).isEqualTo(2);
    }

    private String containerName() {
        return "sokar-uc-shell-" + ProcessHandle.current().pid();
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
        Files.writeString(state.resolve("vault.pid"), String.valueOf(helper.pid()));

        execute(context, "task", "run", "--attach", "shell", "-p", projectFile(dir, MINIMAL).toString(), "--no-attach");

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

        execute(context, "task", "run", "-p", projectFile(dir, MINIMAL).toString(),
                "--no-attach", "--keep");

        assertThat(state.resolve("vault.pid")).exists();
    }

    @Test
    void removesTheContainerWhenTheShellExits(@TempDir Path dir) throws IOException {

        // The bug this exists for: attaching used to replace this process, so nothing was left to
        // remove the container. Every interactive task leaked one while printing the opposite.
        execute(context(dir, true), "task", "run", "-p", projectFile(dir, MINIMAL).toString());

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

        final int code = execute(context(dir, true), "task", "run", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(out.toString()).doesNotContain("it failed");
        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        // The code still reaches the caller, the way ssh reports a remote command's status.
        assertThat(code).isEqualTo(127);
    }

    @Test
    void keepsATaskWhoseSessionNeverFinished(@TempDir Path dir) throws IOException {

        // The other half of the same decision. Somebody who walked out of a shell ended the task;
        // somebody whose terminal died did not, and the workspace may hold commits that never
        // reached the gate. So this one is stopped and held rather than removed.
        execDies = true;
        runner.answering("container inspect", "c0ffee\n");

        execute(context(dir, true), "task", "run", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(runner.lines()).as("an interrupted run must not be swept up")
                .noneMatch(line -> line.contains("rm --force"));
    }

    @Test
    void labelsTheContainerWithItsProjectAndClass(@TempDir Path dir) throws IOException {

        // So a listing can still say what a task belongs to after a reboot. The sidecar holding
        // the same two facts is in $XDG_RUNTIME_DIR and does not survive one.
        execute(context(dir, true), "task", "run", "--attach", "shell", "--no-attach",
                "-p", projectFile(dir, MINIMAL).toString());

        assertThat(runner.lines()).anyMatch(line -> line.contains("create")
                && line.contains("--label org.fuin.sokar.project=uc")
                && line.contains("--label org.fuin.sokar.class=guarded"));
    }

    @Test
    void leavesTheContainerAloneWithKeep(@TempDir Path dir) throws IOException {

        // The negative case: --keep has to mean something, and today it did not.
        execute(context(dir, true), "task", "run", "--keep",
                "-p", projectFile(dir, MINIMAL).toString());

        // 'rm --force', which is how a task container is removed - not any argument containing
        // "rm". A throwaway container elsewhere in the run legitimately passes --rm, and matching
        // that made this fail for the wrong reason.
        assertThat(runner.invocations()).noneSatisfy(command ->
                assertThat(command.describe()).contains("rm --force"));
    }

    @Test
    void attachesAPlainShellWhenAsked(@TempDir Path dir) throws IOException {

        // The negative case: --attach shell must not wrap anything around the shell.
        execute(context(dir, true), "task", "run", "--attach", "shell",
                "-p", projectFile(dir, MINIMAL).toString());

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
}
