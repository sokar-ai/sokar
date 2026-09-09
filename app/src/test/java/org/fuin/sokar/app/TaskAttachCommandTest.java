package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for opening a shell in a running task.
 * <p>
 * The arguments are the whole behaviour: this command replaces its own process with the session,
 * so what it builds is what a person ends up talking to. Getting the program and its arguments
 * joined into one string would have podman look for an executable with spaces in its name, which
 * fails as "no such file" and reads like a broken container.
 */
class TaskAttachCommandTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final List<String> attached = new ArrayList<>();

    private final StringWriter err = new StringWriter();

    private int run(Path dir, String... arguments) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(runner,
                new SokarPaths(xdg, dir.resolve("bin")), command -> {
                    attached.addAll(command);
                    return 0;
                });
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(new StringWriter()));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(arguments);
    }

    @Test
    void attachesToASessionThatIsFoundAgainOnReturn(@TempDir Path dir) {

        // 'new-session -A' is attach-or-create in one call, so coming back and starting for the
        // first time are the same operation - and the one that matters is exercised every time
        // rather than only once.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        assertThat(run(dir, "task", "attach", "sokar-uc-shell-1")).isZero();

        assertThat(attached).containsSubsequence("exec", "--interactive", "--tty",
                "sokar-uc-shell-1", "tmux", "-f", "/etc/sokar/tmux.conf",
                "new-session", "-A", "-s", "sokar");
    }

    @Test
    void keepsTheProgramAndItsArgumentsApart(@TempDir Path dir) {

        // Joined into one string, podman looks for an executable called
        // "tmux new-session -A -s sokar" and fails with "no such file" - which reads as a broken
        // container rather than as a mistake here.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        run(dir, "task", "attach", "sokar-uc-shell-1");

        assertThat(attached).noneMatch(argument -> argument.contains(" "));
        assertThat(attached).contains("tmux");
    }

    @Test
    void aStoppedTaskIsSentToResumeRatherThanToTheList(@TempDir Path dir) {

        // Two different things a person has to do next. "No such task" would send somebody
        // looking at the list for something that is right there, stopped.
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 2 minutes ago\n");

        assertThat(run(dir, "task", "attach", "sokar-uc-shell-1")).isEqualTo(69);
        assertThat(err.toString()).contains("is not running").contains("task resume");
        assertThat(attached).as("nothing was attached to").isEmpty();
    }

    @Test
    void aTaskThatIsNotThereIsSentToTheList(@TempDir Path dir) {

        runner.answering("ps", "");

        assertThat(run(dir, "task", "attach", "sokar-uc-shell-1")).isEqualTo(69);
        assertThat(err.toString()).contains("no task called").contains("task list");
    }

    @Test
    void somethingThatIsNotATaskIsRefusedOnItsNameAlone(@TempDir Path dir) {

        // The refusal is decided by the name, before anything about the machine is consulted.
        // What follows it is a listing, offered because somebody who typed a name that is not a
        // task is exactly who needs to see the ones that are - and it is read-only. A podman
        // that cannot answer costs nothing here: the offer swallows it and the refusal stands.
        assertThat(run(dir, "task", "attach", "some-other-container")).isEqualTo(69);
        assertThat(attached).as("nothing is attached to a name that is not a task").isEmpty();
        assertThat(runner.lines()).as("only ever asked to list, never to act")
                .allMatch(line -> line.startsWith("podman ps"));
    }

    @Test
    void anOfflineProjectIsNotRefused(@TempDir Path dir) {

        // The class governs egress - what resolves and what leaves - and a person typing in a
        // container is neither. Offline is precisely the case where somebody has to work by hand,
        // because the agent reaches nothing; refusing would remove the only way in.
        runner.answering("ps", "sokar-offlineproj-shell-1\tUp 4 minutes\n");

        assertThat(run(dir, "task", "attach", "sokar-offlineproj-shell-1")).isZero();
        assertThat(attached).contains("tmux");
    }

    @Test
    void readsThePinnedConfigurationRatherThanWhateverIsAround(@TempDir Path dir) {

        // What a session remembers IS the answer to "what may re-entering claim". Left to tmux's
        // own search it would come from the image, or a dotfile, or a default nobody chose - and
        // then the one process that has to state the figure does not know it.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        run(dir, "task", "attach", "sokar-uc-shell-1");

        assertThat(attached).containsSubsequence("tmux", "-f", "/etc/sokar/tmux.conf");
    }
}
