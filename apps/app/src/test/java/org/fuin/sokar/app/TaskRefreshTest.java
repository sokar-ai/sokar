package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GitGate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link TaskRefresh}: a task's repository brought up to its source, and its agent told what moved.
 */
class TaskRefreshTest {

    private static final String TASK = "sokar-default-app-writer";

    @TempDir
    Path dir;

    private final CommandRunner real = new ProcessCommandRunner();

    private String git(final Path in, final String... arguments) {
        final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", in.toString(), "-c",
                "user.email=t@example.com", "-c", "user.name=T"));
        command.addAll(List.of(arguments));
        final CommandResult result = real.run(Command.of(command));
        assertThat(result.successful()).as(result.standardError()).isTrue();
        return result.standardOutput().strip();
    }

    @Test
    void aCheckoutThatMovedOnReachesTheGateAndTheAgentIsToldToFetchIt() throws Exception {

        // Only a new task's start brought the source in; a running or restarted task never heard of it.
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        // The task as podman lists it; every other command is real.
        final CommandRunner runner = command -> command.arguments().size() > 1 && command.arguments().get(0).endsWith("podman")
                && command.arguments().get(1).equals("ps")
                ? new CommandResult(command, 0, TASK + "\tUp 2 minutes\t\t\tdefault\tguarded\tapp\t\tid\n", "")
                : real.run(command);
        final SokarContext context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        git(checkout, "init", "-q", "-b", "main");
        Files.writeString(checkout.resolve("README.md"), "app\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-q", "-m", "start");
        WorkOrigin.fromCheckout(context, checkout);
        final Project project = GateSupport.byName(context, DefaultProject.NAME);
        final GitGate gate = GateSupport.gate(context, project, project.repository("app"), null, null);
        gate.initialize();
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(TASK));
        mailbox.create();

        assertThat(new TaskRefresh(context).refresh(TASK).outcome()).as("nothing moved yet")
                .isEqualTo(TaskRefresh.Outcome.UNCHANGED);

        Files.writeString(checkout.resolve("more.txt"), "the person goes on\n");
        git(checkout, "add", ".");
        git(checkout, "commit", "-q", "-m", "more");
        final String moved = git(checkout, "rev-parse", "HEAD");
        final TaskRefresh.Result result = new TaskRefresh(context).refresh(TASK);

        assertThat(result.outcome()).isEqualTo(TaskRefresh.Outcome.MOVED);
        assertThat(result.moved()).containsEntry("main", moved);
        assertThat(gate.branches()).containsEntry("main", moved);
        assertThat(result.told()).isTrue();
        try (var told = Files.list(mailbox.inboxNew())) {
            assertThat(told.map(file -> {
                try {
                    return Files.readString(file);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }).toList()).singleElement().asString().contains("git fetch sokar").contains(moved.substring(0, 12));
        }

        // The short name 'sokar approve' takes is taken too.
        assertThat(new TaskRefresh(context).refresh("app-writer").outcome()).isEqualTo(TaskRefresh.Outcome.UNCHANGED);
        assertThat(new TaskRefresh(context).refresh("sokar-default-app-nobody").outcome())
                .isEqualTo(TaskRefresh.Outcome.NO_SUCH_TASK);
    }
}
