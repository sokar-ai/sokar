package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TaskWorkspace}.
 */
class TaskWorkspaceTest {

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    private String git(Path directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git", "-c", "user.name=T",
                "-c", "user.email=t@example.com", "-c", "init.defaultBranch=main"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(new Command(all, directory,
                Map.of("GIT_TERMINAL_PROMPT", "0", "LC_ALL", "C"), null)).standardOutput().strip();
    }

    /** A repository with one commit on the given branch, and a mirror of it. */
    private Path seededMirror(Path root, String branch) throws IOException {
        final Path source = Files.createDirectories(root.resolve("source"));
        git(source, "init", "-q", "-b", branch, ".");
        Files.writeString(source.resolve("README.md"), "the project\n");
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/Main.java"), "class Main {}\n");
        git(source, "add", "-A");
        git(source, "commit", "-q", "-m", "initial");
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "-q", "--mirror", source.toString(), mirror.toString());
        return mirror;
    }

    /** Runs the workspace script the way the container does, with the mirror as the remote. */
    private void runCloneScript(Path workspace, Path mirror, String taskRef) {
        final String script = TaskWorkspace.cloneScript()
                .replace("cd " + TaskWorkspace.MOUNT + ";", "cd " + workspace + ";");
        runner.runOrFail(new Command(List.of("sh", "-c", script), workspace,
                Map.of("SOKAR_REMOTE_URL", mirror.toString(), "SOKAR_TASK_REF", taskRef,
                        "GIT_TERMINAL_PROMPT", "0"), null));
    }

    @Test
    void aRemoteThatNeverAnswersIsGivenUpOnAndSaidRatherThanWaitedForSilently(@TempDir Path root) throws Exception {

        // An online task's remote over ssh, its port dropped: the fetch never ended, and the launch waited with it.
        final Path workspace = Files.createDirectories(root.resolve("workspace"));
        final String script = TaskWorkspace.cloneScript(2)
                .replace("cd " + TaskWorkspace.MOUNT + ";", "cd " + workspace + ";");
        final ProcessBuilder builder = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        builder.environment().put("SOKAR_REMOTE_URL", "ssh://git@forge.invalid/you/repo.git");
        builder.environment().put("GIT_SSH_COMMAND", "sleep 30; :");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        final long started = System.nanoTime();
        final Process process = builder.start();
        final boolean ended = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
        final String said = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        process.destroyForcibly();

        assertThat(ended).as("the script gave up on the fetch").isTrue();
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(15));
        assertThat(said).as("and said why, for the launch to report").contains(TaskWorkspace.FETCH_FAILED + " 124");
        assertThat(TaskWorkspace.fetchProblem(said)).contains("did not answer within");
        assertThat(TaskWorkspace.fetchProblem("nothing of the kind\n")).isNull();
    }

    /** Runs the script with a remote whose ssh refuses, as GitHub refuses a task holding no key; returns its output. */
    private String refusedFetch(final Path workspace) throws Exception {
        final String script = TaskWorkspace.cloneScript(10)
                .replace("cd " + TaskWorkspace.MOUNT + ";", "cd " + workspace + ";");
        final ProcessBuilder builder = new ProcessBuilder("sh", "-c", script).redirectErrorStream(true);
        builder.environment().put("SOKAR_REMOTE_URL", "git@forge.invalid:you/repo.git");
        builder.environment().put("GIT_SSH_COMMAND", "echo 'git@forge.invalid: Permission denied (publickey).' >&2; exit 255; :");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        final Process process = builder.start();
        assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("the script itself still ends well; the launch decides").isZero();
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void theBranchTracksTheTasksOwnPlaceSoStatusSaysWhatWasHandedIn(@TempDir Path root) throws IOException {

        // After 'git push' landed on the task's ref, 'git status' still said "ahead of
        // 'sokar/develop' by 1 commit" - the gate's branch, which moves only on approval. An agent pushes again.
        final Path mirror = seededMirror(root, "main");
        final Path workspace = Files.createDirectories(root.resolve("workspace"));
        runCloneScript(workspace, mirror, "refs/sokar/incoming/shell");
        assertThat(git(workspace, "status", "-sb").lines().findFirst().orElseThrow()).as("nothing done yet")
                .contains("sokar/incoming/shell").doesNotContain("ahead");
        git(workspace, "fetch", "sokar");

        Files.writeString(workspace.resolve("NEW.md"), "the agent's work\n");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-q", "-m", "agent work");
        assertThat(git(workspace, "status", "-sb").lines().findFirst().orElseThrow()).contains("ahead 1");

        git(workspace, "push", "-q", "sokar");
        assertThat(git(workspace, "status", "-sb").lines().findFirst().orElseThrow()).as("handed in")
                .contains("sokar/incoming/shell").doesNotContain("ahead");

        // Again, as a resumed task runs it: nothing doubles, nothing handed in is forgotten.
        runCloneScript(workspace, mirror, "refs/sokar/incoming/shell");
        assertThat(git(workspace, "config", "--get-all", "remote.sokar.fetch").lines()
                .filter(line -> line.contains("incoming")).count()).isEqualTo(1);
        assertThat(git(workspace, "status", "-sb").lines().findFirst().orElseThrow()).doesNotContain("ahead");
    }

    @Test
    void sendsABarePushToTheGateRatherThanIntoTheMirror(@TempDir Path root) throws IOException {

        // Checking out from a remote-tracking branch makes the branch track sokar/main, so a
        // bare 'git push' landed on refs/heads/main in the mirror - reporting success while
        // nothing ever appeared for review. A push that silently misses the gate is the failure
        // this product exists to prevent, and it was one obvious command away.
        final Path mirror = seededMirror(root, "main");
        final Path workspace = Files.createDirectories(root.resolve("workspace"));
        runCloneScript(workspace, mirror, "refs/sokar/incoming/shell");

        Files.writeString(workspace.resolve("NEW.md"), "the agent's work\n");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-q", "-m", "agent work");
        git(workspace, "push");

        assertThat(git(mirror, "for-each-ref", "--format=%(refname)"))
                .contains("refs/sokar/incoming/shell");
        // And not onto the branch, which is where it used to go.
        assertThat(git(mirror, "rev-parse", "refs/heads/main"))
                .isNotEqualTo(git(workspace, "rev-parse", "HEAD"));
    }

    @Test
    void sendsABarePushFromABranchTheAgentMadeItself(@TempDir Path root) throws IOException {

        // An agent working on a branch of its own name, which is the ordinary case.
        final Path mirror = seededMirror(root, "main");
        final Path workspace = Files.createDirectories(root.resolve("workspace"));
        runCloneScript(workspace, mirror, "refs/sokar/incoming/shell");

        git(workspace, "checkout", "-q", "-b", "what-the-agent-called-it");
        Files.writeString(workspace.resolve("NEW.md"), "the agent's work\n");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-q", "-m", "agent work");
        git(workspace, "push");

        assertThat(git(mirror, "for-each-ref", "--format=%(refname)"))
                .contains("refs/sokar/incoming/shell");
    }

    @Test
    void keepsTheBarePushUnambiguousWhenTheAgentAddsARemote(@TempDir Path root) throws IOException {

        // What pushDefault is actually for, measured rather than assumed: with one remote git
        // falls back to it and the default is redundant, but an agent that adds the real
        // upstream as a second remote makes a bare push ambiguous - git 2.53 answers with a bare
        // "git push <Name>" usage line and sends nothing. The default keeps it going to the gate.
        final Path mirror = seededMirror(root, "main");
        final Path elsewhere = root.resolve("elsewhere.git");
        git(root, "clone", "-q", "--mirror", root.resolve("source").toString(),
                elsewhere.toString());
        final Path workspace = Files.createDirectories(root.resolve("workspace"));
        runCloneScript(workspace, mirror, "refs/sokar/incoming/shell");
        git(workspace, "remote", "add", "upstream", elsewhere.toString());

        git(workspace, "checkout", "-q", "-b", "what-the-agent-called-it");
        Files.writeString(workspace.resolve("NEW.md"), "the agent's work\n");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-q", "-m", "agent work");
        git(workspace, "push");

        assertThat(git(mirror, "for-each-ref", "--format=%(refname)"))
                .contains("refs/sokar/incoming/shell");
        assertThat(git(elsewhere, "for-each-ref", "--format=%(refname)"))
                .as("and not to the upstream the agent added")
                .doesNotContain("what-the-agent-called-it");
    }

    private void runCloneScript(Path workspace, Path mirror) {
        // The only substitution is the mount point: the container has /workspace and this
        // machine has a temporary directory. Everything else is the script that really runs.
        final String script = TaskWorkspace.cloneScript()
                .replace("cd " + TaskWorkspace.MOUNT + ";", "cd " + workspace + ";");
        runner.runOrFail(new Command(List.of("sh", "-c", script), workspace,
                Map.of("SOKAR_REMOTE_URL", mirror.toString(), "GIT_TERMINAL_PROMPT", "0"), null));
    }

    @Test
    void checksOutWhatItFetched(@TempDir Path root) throws IOException {

        // It used to fetch and stop, so every task began in a directory holding nothing but
        // .git - an agent asked to change a project could not see one file of it. Reported from
        // a machine where 'ls -alF' in the workspace showed exactly that.
        final Path mirror = seededMirror(root, "main");
        final Path workspace = Files.createDirectories(root.resolve("workspace"));

        runCloneScript(workspace, mirror);

        assertThat(workspace.resolve("README.md")).exists();
        assertThat(workspace.resolve("src/Main.java")).exists();
        assertThat(git(workspace, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("main");
    }

    @Test
    void takesTheBranchTheMirrorActuallyHas(@TempDir Path root) throws IOException {

        // A repository seeded from a checkout on 'master' has no 'main' at all, and assuming one
        // would leave the workspace empty for exactly the projects that are oldest.
        final Path mirror = seededMirror(root, "master");
        final Path workspace = Files.createDirectories(root.resolve("workspace"));

        runCloneScript(workspace, mirror);

        assertThat(workspace.resolve("README.md")).exists();
        assertThat(git(workspace, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("master");
    }

    @Test
    void findsTheBranchWhenTheMirrorCannotSayWhichItsHeadIs(@TempDir Path root) throws IOException {

        // A mirror made by 'git init --bare' has HEAD on refs/heads/main whatever is pushed into
        // it, so a repository on 'master' leaves HEAD naming a branch that does not exist. Then
        // 'git remote set-head -a' fails outright - "cannot determine remote HEAD" - and nothing
        // resolves refs/remotes/sokar/HEAD. Measured; this is the case the candidate list is for,
        // and it is how an empty gate mirror is created rather than a contrived one.
        final Path source = Files.createDirectories(root.resolve("source"));
        git(source, "init", "-q", "-b", "master", ".");
        Files.writeString(source.resolve("README.md"), "the project\n");
        git(source, "add", "-A");
        git(source, "commit", "-q", "-m", "initial");

        final Path mirror = root.resolve("mirror.git");
        git(root, "init", "-q", "--bare", mirror.toString());
        git(source, "push", "-q", mirror.toString(), "master");
        assertThat(Files.readString(mirror.resolve("HEAD"))).contains("refs/heads/main");

        final Path workspace = Files.createDirectories(root.resolve("workspace"));
        runCloneScript(workspace, mirror);

        assertThat(workspace.resolve("README.md")).exists();
        assertThat(git(workspace, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("master");
    }

    @Test
    void leavesAnEmptyMirrorAsAnEmptyWorkspace(@TempDir Path root) throws IOException {

        // A project with no history yet. It must succeed and check out nothing, rather than fail
        // and take the task down with it.
        final Path mirror = root.resolve("empty.git");
        git(root, "init", "-q", "--bare", mirror.toString());
        final Path workspace = Files.createDirectories(root.resolve("workspace"));

        runCloneScript(workspace, mirror);

        assertThat(workspace.resolve(".git")).isDirectory();
        try (var entries = Files.list(workspace)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .containsExactly(".git");
        }
    }

    @Test
    void neverThrowsAwayWorkWhenTheTaskIsResumed(@TempDir Path root) throws IOException {

        // The same script runs again on resume. A workspace holding commits the agent made, or
        // edits it has not committed, must survive that - which is why the checkout is guarded on
        // an unborn HEAD rather than on an empty directory.
        final Path mirror = seededMirror(root, "main");
        final Path workspace = Files.createDirectories(root.resolve("workspace"));
        runCloneScript(workspace, mirror);

        Files.writeString(workspace.resolve("NEW.md"), "the agent's own work\n");
        git(workspace, "add", "-A");
        git(workspace, "commit", "-q", "-m", "agent work");
        Files.writeString(workspace.resolve("README.md"), "edited, not committed\n");

        runCloneScript(workspace, mirror);

        assertThat(workspace.resolve("NEW.md")).exists();
        assertThat(Files.readString(workspace.resolve("README.md")))
                .isEqualTo("edited, not committed\n");
        assertThat(git(workspace, "log", "--oneline", "-1")).contains("agent work");
    }

    @Test
    void theGateAddressIsAConstantNotALookup() {

        // The name podman uses does not exist on the host, so resolving it here returned null and
        // the firewall rule for the gate was silently left out. It must be a constant.
        assertThat(TaskWorkspace.gateAddress()).isEqualTo("169.254.1.2");
    }

    @Test
    void readsTheHostFromAnSshRemote() {

        // Not a URL, and the common shape for a git remote. The firewall and the resolver both
        // need the host, so getting this wrong means an online task cannot reach its upstream.
        assertThat(TaskRunCommand.upstreamHost("git@github.com:you/repo.git"))
                .isEqualTo("github.com");
    }

    @Test
    void readsTheHostFromAnHttpsRemote() {

        assertThat(TaskRunCommand.upstreamHost("https://github.com/you/repo.git"))
                .isEqualTo("github.com");
        assertThat(TaskRunCommand.upstreamHost("https://user@git.example.com:8443/repo.git"))
                .isEqualTo("git.example.com");
    }

    @Test
    void saysNothingRatherThanGuessing() {

        // A host it cannot read must not become a firewall rule for the wrong name.
        assertThat(TaskRunCommand.upstreamHost(null)).isNull();
        assertThat(TaskRunCommand.upstreamHost("   ")).isNull();
        assertThat(TaskRunCommand.upstreamHost("/srv/git/repo.git")).isNull();
    }

    @Test
    void acceptsTheMappingPodmanWrites() {

        // Given
        final String hosts = """
                127.0.0.1\tlocalhost
                169.254.1.2\thost.containers.internal host.docker.internal
                """;

        // When & Then
        assertThat(TaskWorkspace.verify(hosts, "169.254.1.2")).isNull();
    }

    @Test
    void reportsADifferentAddress() {

        // Given
        final String hosts = "10.0.2.2\thost.containers.internal\n";

        // When
        final String result = TaskWorkspace.verify(hosts, "169.254.1.2");

        // Then
        assertThat(result).contains("10.0.2.2").contains("firewalled off");
    }

    @Test
    void acceptsWhateverAddressTheRulesetWasOpenedFor() {

        // Under slirp4netns a container reaches the host at its LAN address, and sokar asks
        // podman rather than assuming. Comparing against the constant instead reported the gate
        // as firewalled off on every such machine while the push worked - measured on Ubuntu.
        final String hosts = "192.168.122.174\thost.containers.internal\n";

        assertThat(TaskWorkspace.verify(hosts, "192.168.122.174")).isNull();
    }

    @Test
    void reportsAMissingEntry() {

        // Given
        final String hosts = "127.0.0.1\tlocalhost\n";

        // When
        final String result = TaskWorkspace.verify(hosts, "169.254.1.2");

        // Then
        assertThat(result).contains("no host.containers.internal entry");
    }

    @Test
    void ignoresCommentsAndBlankLines() {

        // Given
        final String hosts = """
                # added by podman
                
                169.254.1.2\thost.containers.internal
                """;

        // When & Then
        assertThat(TaskWorkspace.verify(hosts, "169.254.1.2")).isNull();
    }
}
