package org.fuin.sokar.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end tests for the gate, driving real {@code git}.
 * <p>
 * Named {@code Test} rather than {@code IT} on purpose: surefire would not run an {@code IT} class
 * and these would then pass by never executing. They need {@code git} on the path, which the
 * product needs anyway.
 * <p>
 * Mocking git here would only test that the arguments match what was written down. What matters is
 * whether a real {@code git push} succeeds against the endpoint and lands where it should, which
 * nothing but a real push can tell you.
 */
class GitGateTest {

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(60));

    @TempDir
    private Path root;

    private Path mirror;

    private Path work;

    @BeforeEach
    void setUp() {
        mirror = root.resolve("mirror.git");
        work = root.resolve("work");
    }

    private CommandResult git(Path directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(new Command(all, directory,
                Map.of("GIT_AUTHOR_NAME", "Test", "GIT_AUTHOR_EMAIL", "test@example.com",
                        "GIT_COMMITTER_NAME", "Test", "GIT_COMMITTER_EMAIL", "test@example.com",
                        "GIT_TERMINAL_PROMPT", "0"),
                null));
    }

    private GitGate gate(GateMode mode, String upstream) {
        final GitGate gate = new GitGate(runner, mirror, mode, upstream);
        gate.initialise();
        return gate;
    }

    private GitHttpServer serve(TaskToken token) {
        final GitHttpServer server = new GitHttpServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), mirror, token,
                new GitSubprocess());
        server.start();
        return server;
    }

    private void makeCommit(String file, String content) throws IOException {
        Files.createDirectories(work);
        Files.writeString(work.resolve(file), content);
        git(work, "add", file);
        git(work, "commit", "-m", "add " + file);
    }

    @Test
    void anAgentPushIsVisibleForReviewAndGoesNoFurther() throws IOException {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        final TaskToken token = TaskToken.mint();

        try (GitHttpServer server = serve(token)) {

            git(root, "init", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            makeCommit("agent.txt", "written by the agent");

            // This is the agent pushing. It has the task token and nothing else - no upstream
            // credentials, no route off the machine.
            git(work, "push", "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                    + ":" + server.port() + "/mirror.git",
                    "HEAD:" + GitGate.INCOMING + "task-1");

            assertThat(gate.pending()).containsExactly("task-1");
        }
    }

    @Test
    void theOperatorCanSeeWhatTheAgentChanged() throws IOException {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        final TaskToken token = TaskToken.mint();

        try (GitHttpServer server = serve(token)) {
            git(root, "init", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            makeCommit("agent.txt", "written by the agent");
            git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-1");
        }

        assertThat(gate.log("task-1", null)).contains("add agent.txt");
    }

    @Test
    void theFirstReviewOfANewProjectWorks() throws IOException {

        // An empty mirror has no branch to compare against. Every project's first review hits
        // this, so it must not be a git error about an ambiguous argument.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        final TaskToken token = TaskToken.mint();

        try (GitHttpServer server = serve(token)) {
            git(root, "init", "--initial-branch=main", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            makeCommit("agent.txt", "written by the agent");
            git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-1");
        }

        assertThat(gate.resolves("main")).as("the mirror has no main branch").isFalse();
        assertThat(gate.review("task-1", "main")).contains("written by the agent");
        assertThat(gate.log("task-1", "main")).contains("add agent.txt");
    }

    @Test
    void aPushWithoutTheTokenIsRefused() throws IOException {

        gate(GateMode.GATEKEEPING, null);

        try (GitHttpServer server = serve(TaskToken.mint())) {
            git(root, "init", work.toString());
            makeCommit("agent.txt", "x");

            // git asking for a username is the visible form of the 401: it received the
            // challenge and had no credentials to answer it with.
            assertThatThrownBy(() -> git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "t"))
                    .hasMessageContaining("could not read Username");
        }
    }

    @Test
    void aPushWithTheWrongTokenIsRefused() throws IOException {

        gate(GateMode.GATEKEEPING, null);

        try (GitHttpServer server = serve(TaskToken.mint())) {
            git(root, "init", work.toString());
            git(work, "config", "http.extraHeader",
                    "Authorization: " + TaskToken.mint().basicAuthorization());
            makeCommit("agent.txt", "x");

            assertThatThrownBy(() -> git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "t"))
                    .hasMessageContaining("could not read Username");
        }
    }

    @Test
    void approvingForwardsToTheUpstreamAndClearsThePending() throws IOException {

        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());

        final GitGate gate = gate(GateMode.GATEKEEPING, upstream.toString());
        final TaskToken token = TaskToken.mint();

        try (GitHttpServer server = serve(token)) {
            git(root, "init", "--initial-branch=main", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            makeCommit("agent.txt", "written by the agent");
            git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-1");
        }

        // Nothing has reached the upstream yet.
        assertThat(runner.runOrFail(Command.of("git", "--git-dir", upstream.toString(),
                "for-each-ref")).standardOutput()).isEmpty();

        gate.approve("task-1", "main");

        assertThat(runner.runOrFail(Command.of("git", "--git-dir", upstream.toString(),
                "log", "--oneline", "main")).standardOutput()).contains("add agent.txt");
        assertThat(gate.pending()).isEmpty();
    }

    @Test
    void anOfflineProjectCannotForwardAtAll() throws IOException {

        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", upstream.toString());

        final GitGate gate = gate(GateMode.OFFLINE, upstream.toString());

        assertThatThrownBy(() -> gate.approve("task-1", "main"))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("nothing is forwarded upstream");
    }

    @Test
    void approvingSomethingThatWasNotPushedIsRefused() {

        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", upstream.toString());
        final GitGate gate = gate(GateMode.GATEKEEPING, upstream.toString());

        // Otherwise a typo would push whatever ref happened to match.
        assertThatThrownBy(() -> gate.approve("never-pushed", "main"))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("no pending push");
    }

    @Test
    void namingSomethingThatIsNotPendingSaysSoRatherThanFailingInGit() {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);

        // A raw "ambiguous argument" from git tells the operator nothing about what they got wrong.
        assertThatThrownBy(() -> gate.review("typo", "main"))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("no pending push named 'typo'")
                .hasMessageContaining("nothing is pending");
        assertThatThrownBy(() -> gate.log("typo", null))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("no pending push");
    }

    @Test
    void rejectingRemovesThePendingPush() throws IOException {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        final TaskToken token = TaskToken.mint();

        try (GitHttpServer server = serve(token)) {
            git(root, "init", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            makeCommit("agent.txt", "x");
            git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-1");
        }

        gate.reject("task-1");

        assertThat(gate.pending()).isEmpty();
    }

    private String url(GitHttpServer server) {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                + ":" + server.port() + "/mirror.git";
    }
}
