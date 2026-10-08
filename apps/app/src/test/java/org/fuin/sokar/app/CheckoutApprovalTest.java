package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GitGate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link CheckoutApproval}, with real git: a checkout a task started from, its gate, and work waiting there.
 */
class CheckoutApprovalTest {

    @TempDir
    Path dir;

    private final CommandRunner runner = new ProcessCommandRunner();

    private SokarContext context;

    private Path checkout;

    private Path origin;

    private GitGate gate;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    /** What the person answers, one line per question; empty: they answer nothing. */
    private final Deque<String> answers = new ArrayDeque<>();

    private final List<String> asked = new java.util.ArrayList<>();

    @BeforeEach
    void aCheckoutATaskStartedFrom() throws Exception {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        origin = dir.resolve("origin.git");
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        checkout = dir.resolve("app");
        git(dir, "clone", "-q", origin.toString(), checkout.toString());
        identity(checkout);
        Files.writeString(checkout.resolve("README.md"), "app\n");
        git(checkout, "add", "README.md");
        git(checkout, "commit", "-q", "-m", "start");
        git(checkout, "push", "-q", "origin", "main");
        // What starting a task in the checkout does: the repository goes into 'default', and its gate is seeded.
        final WorkOrigin.Choice choice = WorkOrigin.fromCheckout(context, checkout);
        final Project project = GateSupport.byName(context, choice.project());
        gate = GateSupport.gate(context, project, project.repository(choice.repository()), null,
                checkout.toString());
        gate.initialize();
    }

    @Test
    void afterYesTheCheckoutHasTheBranchAndItsWorkingCopyAndOriginAreUntouched() throws Exception {
        final String work = agentPushes("writer", "the agent's change");
        answers.add("y");

        assertThat(approve(null, false)).as(err.toString()).isZero();

        assertThat(git(checkout, "rev-parse", "sokar/writer")).isEqualTo(work);
        assertThat(git(checkout, "symbolic-ref", "--short", "HEAD")).as("the branch checked out").isEqualTo("main");
        assertThat(git(checkout, "status", "--porcelain")).as("the working copy").isEmpty();
        assertThat(git(origin, "branch", "--list", "sokar/*")).as("nothing reaches the forge").isEmpty();
        assertThat(out.toString()).as("what came was shown before the question").contains("the agent's change")
                .contains("+the agent's change");
        assertThat(asked).singleElement().asString().contains("sokar/writer");
        assertThat(gate.pending()).as("brought back, so no longer waiting").isEmpty();
    }

    @Test
    void afterNoNothingChangesAndTheWorkStillWaits() throws Exception {
        agentPushes("writer", "kept for later");
        answers.add("n");

        assertThat(approve(null, false)).isNotZero();

        assertThat(git(checkout, "branch", "--list", "sokar/*")).isEmpty();
        assertThat(gate.pending()).containsExactly("writer");
    }

    @Test
    void nothingWaitingIsSaidAndIsNoSuccess() {
        assertThat(approve(null, true)).isNotZero();
        assertThat(err.toString()).contains("nothing waits at the gate");
    }

    @Test
    void twoTasksAreNamedAndOneIsAskedForOrTakenByItsName() throws Exception {
        agentPushes("writer", "one");
        final String review = agentPushes("review", "two");

        assertThat(approve(null, true)).as("a script that names none is refused").isNotZero();
        assertThat(err.toString()).contains("writer").contains("review");

        answers.add("review");
        answers.add("y");
        assertThat(approve(null, false)).as(err.toString()).isZero();
        assertThat(git(checkout, "rev-parse", "sokar/review")).isEqualTo(review);

        assertThat(approve("writer", true)).as(err.toString()).isZero();
        assertThat(git(checkout, "branch", "--list", "sokar/writer")).isNotEmpty();
    }

    @Test
    void aBranchThatDoesNotFastForwardIsNotOverwritten() throws Exception {
        git(checkout, "branch", "sokar/writer");
        git(checkout, "checkout", "-q", "sokar/writer");
        Files.writeString(checkout.resolve("mine.txt"), "the person's own\n");
        git(checkout, "add", "mine.txt");
        git(checkout, "commit", "-q", "-m", "mine");
        final String mine = git(checkout, "rev-parse", "HEAD");
        git(checkout, "checkout", "-q", "main");
        agentPushes("writer", "the agent's");

        assertThat(approve("writer", false)).isNotZero();

        assertThat(git(checkout, "rev-parse", "sokar/writer")).isEqualTo(mine);
        assertThat(err.toString()).contains("fast-forward");
        assertThat(asked).as("refused before anybody is asked to say yes to it").isEmpty();
        assertThat(gate.pending()).containsExactly("writer");
    }

    @Test
    void aCommitTheRemoteNeverSawReachesTheGateFromTheCheckout() throws Exception {

        // The checkout is the source: what is committed here goes in, pushed to origin or not.
        Files.writeString(checkout.resolve("local.txt"), "never pushed\n");
        git(checkout, "add", "local.txt");
        git(checkout, "commit", "-q", "-m", "local only");
        final String local = git(checkout, "rev-parse", "HEAD");

        assertThat(gate.refresh()).isEmpty();

        assertThat(git(gate.mirror(), "rev-parse", "main")).as("the mirror follows the checkout").isEqualTo(local);
        assertThat(git(origin, "rev-parse", "main")).as("origin never saw it").isNotEqualTo(local);
    }

    @Test
    void workOfARepositoryWhoseSourceIsTheRemoteIsNotBroughtIntoTheCheckout() throws Exception {

        // Added by its address, as every entry from before the checkout was a source: its work goes to the remote.
        final Path other = dir.resolve("other");
        git(dir, "clone", "-q", origin.toString(), other.toString());
        final DefaultProject.Entry entry = new DefaultProject(context).add(origin.toString(), "by-address", "");
        final Project project = GateSupport.byName(context, DefaultProject.NAME);
        final GitGate remote = GateSupport.gate(context, project, project.repository(entry.name()), null, null);
        remote.initialize();
        final Path work = dir.resolve("work-remote");
        git(dir, "clone", "-q", remote.mirror().toString(), work.toString());
        identity(work);
        Files.writeString(work.resolve("r.txt"), "for the remote\n");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", "for the remote");
        git(work, "push", "-q", remote.mirror().toString(), "HEAD:" + GitGate.INCOMING + "remote-task");

        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final int code = new CheckoutApproval(context, new PrintWriter(out, true), new PrintWriter(err, true))
                .run(other, null, true, question -> "y");

        assertThat(code).isNotZero();
        assertThat(err.toString()).contains("goes to").contains("sokar gate approve");
        assertThat(git(other, "branch", "--list", "sokar/*")).isEmpty();
        assertThat(remote.pending()).containsExactly("remote-task");
    }

    @Test
    void nothingAtTheGateButWorkInTheTaskIsSaidWithHowToBringItThere() throws Exception {

        // The agent committed and never pushed; 'approve' said only that nothing waited.
        final String repository = new DefaultProject(context).withUpstream(checkout.toRealPath().toString()).name();
        final String task = "sokar-default-" + repository + "-writer";
        final CommandRunner podman = command -> {
            final List<String> arguments = command.arguments();
            if (arguments.size() > 1 && arguments.get(0).endsWith("podman") && arguments.get(1).equals("ps")) {
                return new CommandResult(command, 0, task + "\tUp 3 minutes\t\t\tdefault\tguarded\t" + repository
                        + "\t\tid\n", "");
            }
            if (arguments.size() > 1 && arguments.get(0).endsWith("podman") && arguments.get(1).equals("exec")) {
                return new CommandResult(command, 0, "1 2", "");
            }
            return runner.run(command);
        };
        final SokarContext withTask = new SokarContext(podman, context.paths(), arguments -> 0);
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        final int code = new CheckoutApproval(withTask, new PrintWriter(out, true), new PrintWriter(err, true))
                .run(checkout, null, true, question -> null);

        assertThat(code).isNotZero();
        assertThat(err.toString()).contains("nothing waits at the gate").contains(task)
                .contains("2 commits and 1 changed file").contains("git push");
    }

    private int approve(final String task, final boolean yes) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        return new CheckoutApproval(context, new PrintWriter(out, true), new PrintWriter(err, true))
                .run(checkout, task, yes, question -> {
                    asked.add(question);
                    return answers.poll();
                });
    }

    /** What an agent does: commits on what the task started from and pushes it to its own ref at the gate. */
    private String agentPushes(final String task, final String line) throws Exception {
        final Path work = dir.resolve("work-" + task);
        git(dir, "clone", "-q", checkout.toString(), work.toString());
        identity(work);
        Files.writeString(work.resolve(task + ".txt"), line + "\n");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", line);
        git(work, "push", "-q", gate.mirror().toString(), "HEAD:" + GitGate.INCOMING + task);
        return git(work, "rev-parse", "HEAD");
    }

    private void identity(final Path repository) {
        git(repository, "config", "user.email", "t@example.com");
        git(repository, "config", "user.name", "T");
    }

    private String git(final Path in, final String... arguments) {
        final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", in.toString()));
        command.addAll(List.of(arguments));
        final CommandResult result = runner.run(Command.of(command));
        assertThat(result.successful()).as(String.join(" ", command) + ": " + result.standardError()).isTrue();
        return result.standardOutput().strip();
    }
}
