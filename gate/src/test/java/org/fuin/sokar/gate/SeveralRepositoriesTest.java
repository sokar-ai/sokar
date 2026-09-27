package org.fuin.sokar.gate;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a project's second repository buys, and what it must not cost - measured with real git.
 * <p>
 * <strong>Argued from the single-repository case, this would be worth nothing.</strong> A project
 * with three repositories has three mirrors, three gates and three review branches, and the claim
 * that unreviewed work cannot reach an upstream has to hold for each of them separately. The
 * failure this guards against is not a gate that forwards - it is a task pushing into one
 * repository and the push landing, or being visible, somewhere else.
 * <p>
 * Named {@code Test} rather than {@code IT} for the reason {@link GitGateTest} gives: surefire
 * would not run an {@code IT} class, and these would pass by never executing.
 */
class SeveralRepositoriesTest {

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(60));

    @TempDir
    private Path root;

    private String git(Path directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(new Command(all, directory,
                Map.of("GIT_AUTHOR_NAME", "Test", "GIT_AUTHOR_EMAIL", "test@example.com",
                        "GIT_COMMITTER_NAME", "Test", "GIT_COMMITTER_EMAIL", "test@example.com",
                        "GIT_TERMINAL_PROMPT", "0"),
                null)).standardOutput();
    }

    /**
     * An upstream somebody else owns: a bare repository with one commit on main.
     *
     * @param name Directory name.
     * @return Its path.
     */
    private Path upstream(String name) throws IOException {
        final Path bare = root.resolve(name + "-upstream.git");
        final Path seed = root.resolve(name + "-seed");
        Files.createDirectories(seed);
        git(root, "init", "--initial-branch=main", seed.toString());
        Files.writeString(seed.resolve("README.md"), "the " + name + " repository\n");
        git(seed, "add", "README.md");
        git(seed, "commit", "-m", "first");
        git(root, "clone", "--bare", seed.toString(), bare.toString());
        return bare;
    }

    /**
     * The mirror of one repository of a project, laid out as Sokar lays it out: the project's own
     * beside the mirrors directory, the ones it names one directory down.
     */
    private Path mirrorOf(String project, String repository) {
        return project.equals(repository)
                ? root.resolve("mirrors").resolve(project + ".git")
                : root.resolve("mirrors").resolve(project).resolve(repository + ".git");
    }

    private GitGate gate(String project, String repository, Path upstream) {
        final GitGate gate = new GitGate(runner, mirrorOf(project, repository),
                GateMode.GATEKEEPING, upstream.toString(), upstream.toString());
        gate.initialize();
        return gate;
    }

    /**
     * Pushes one commit through a gate the way an agent does, and returns nothing: what the test
     * looks at afterwards is where it landed.
     */
    private void pushThroughGate(GitGate gate, Path mirror, String task, String file)
            throws IOException {

        final TaskToken token = TaskToken.mint();
        final Path work = root.resolve("work-" + task);

        try (GitHttpServer server = new GitHttpServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), mirror, token,
                new GitSubprocess())) {
            server.start();
            git(root, "clone", mirror.toString(), work.toString());
            Files.writeString(work.resolve(file), "written by the agent\n");
            git(work, "add", file);
            git(work, "commit", "-m", "add " + file);
            git(work, "config", "http.extraHeader",
                    "Authorization: " + token.basicAuthorization());
            git(work, "push", "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                    + ":" + server.port() + "/" + mirror.getFileName(),
                    "HEAD:" + GitGate.INCOMING + task);
        }
    }

    /**
     * Returns the branches an upstream has, so a forwarded push would be visible.
     * <p>
     * Asked of git rather than read from {@code refs/heads/}: a freshly cloned bare repository
     * keeps its refs in {@code packed-refs}, so listing the directory answers "no branches" for a
     * repository that has one - which would have made every assertion below pass for the wrong
     * reason.
     */
    private List<String> branchesOf(Path bare) {
        return git(bare, "for-each-ref", "--format=%(refname:short)", "refs/heads")
                .lines().filter(line -> !line.isBlank()).sorted().toList();
    }

    @Test
    void aPushIntoOneRepositoryWaitsThereAndReachesNoUpstream() throws IOException {

        final Path backendUpstream = upstream("backend");
        final Path frontendUpstream = upstream("frontend");
        final GitGate backend = gate("acme", "backend", backendUpstream);
        final GitGate frontend = gate("acme", "frontend", frontendUpstream);

        pushThroughGate(backend, mirrorOf("acme", "backend"), "task-1", "backend.txt");

        // Waiting in the repository it was pushed to.
        assertThat(backend.pending()).containsExactly("task-1");

        // And nowhere near that repository's upstream. This is the claim the gate makes for one
        // repository, measured again for one repository OF A PROJECT THAT HAS SEVERAL - the
        // mirror is a different directory now, and a gate built on the wrong one would forward
        // or refuse without anybody noticing which.
        assertThat(branchesOf(backendUpstream)).containsExactly("main");
    }

    @Test
    void oneRepositoriesWorkIsInvisibleInAnother() throws IOException {

        final Path backendUpstream = upstream("backend");
        final Path frontendUpstream = upstream("frontend");
        final GitGate backend = gate("acme", "backend", backendUpstream);
        final GitGate frontend = gate("acme", "frontend", frontendUpstream);

        pushThroughGate(backend, mirrorOf("acme", "backend"), "task-1", "backend.txt");

        // The other repository of the same project has nothing waiting. If the two shared a
        // mirror, 'what is waiting for review' would have two answers and a person approving the
        // frontend would be approving the backend's work.
        assertThat(frontend.pending()).isEmpty();
        assertThat(branchesOf(frontendUpstream)).containsExactly("main");
    }

    @Test
    void approvingOneRepositoryLeavesTheOtherAlone() throws IOException {

        final Path backendUpstream = upstream("backend");
        final Path frontendUpstream = upstream("frontend");
        final GitGate backend = gate("acme", "backend", backendUpstream);
        final GitGate frontend = gate("acme", "frontend", frontendUpstream);

        pushThroughGate(backend, mirrorOf("acme", "backend"), "task-1", "backend.txt");
        pushThroughGate(frontend, mirrorOf("acme", "frontend"), "task-2", "frontend.txt");

        // The sharpest version of the claim: the backend's gate cannot approve the frontend's
        // push at all. If the two shared a mirror this would succeed, and one repository's
        // unreviewed work would land on ANOTHER repository's upstream - approved by somebody who
        // was looking at a different project entirely.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> backend.approve("task-2", "task-2"))
                .isInstanceOf(GateException.class);

        backend.approve("task-1", "task-1");

        // Approved work reaches ITS repository's upstream...
        assertThat(branchesOf(backendUpstream)).contains("task-1");
        // ...and the other repository's push is still waiting, unapproved and unsent. One
        // approval, one repository: that is what a task working on exactly one of them is for.
        assertThat(frontend.pending()).containsExactly("task-2");
        assertThat(branchesOf(frontendUpstream)).containsExactly("main");
    }
}
