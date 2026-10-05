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
        gate.initialize();
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
    void theReviewNamesWhatIsDangerousByKindFirstAndReformattingLast() throws IOException {

        // A one-line workflow change beside a large reformat: the workflow is the finding, and a raw
        // patch in path order put the reformat first.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        final TaskToken token = TaskToken.mint();

        try (GitHttpServer server = serve(token)) {
            git(root, "init", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            makeCommit("app.txt", "one\ntwo\n");
            makeCommit("notes.txt", "a\nb\n");
            Files.writeString(work.resolve("app.txt"), "one  \n\ntwo\n");
            Files.writeString(work.resolve("notes.txt"), "a\nb\nc\n");
            Files.createDirectories(work.resolve(".github/workflows"));
            Files.writeString(work.resolve(".github/workflows/ci.yml"), "on: push\n");
            Files.writeString(work.resolve("api.go"), "// Code generated by a tool. DO NOT EDIT.\npackage api\n");
            git(work, "add", ".");
            git(work, "commit", "-m", "the agent's change");
            git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-1");
        }

        final ReviewRanking.Review review = gate.rankedReview("task-1", null);

        assertThat(review.files()).extracting(ReviewRanking.File::path, ReviewRanking.File::rank).containsExactly(
                org.assertj.core.groups.Tuple.tuple(".github/workflows/ci.yml", ReviewRanking.Rank.DANGEROUS),
                org.assertj.core.groups.Tuple.tuple("notes.txt", ReviewRanking.Rank.ORDINARY),
                org.assertj.core.groups.Tuple.tuple("api.go", ReviewRanking.Rank.GENERATED),
                org.assertj.core.groups.Tuple.tuple("app.txt", ReviewRanking.Rank.REFORMATTING));
        assertThat(review.files().getFirst().reason()).contains("CI definition");
        // The patch follows the same order, not the tree's.
        assertThat(review.patch().indexOf("ci.yml")).isLessThan(review.patch().indexOf("notes.txt"));
        assertThat(review.patch().indexOf("notes.txt")).isLessThan(review.patch().indexOf("api.go"));
        assertThat(review.patch().indexOf("api.go")).isLessThan(review.patch().indexOf("app.txt"));
    }

    @Test
    void aTaskMayPushToItsOwnRefAndToNothingElseInTheMirror() throws IOException {

        // Measured on 2026-09-29: a plain push landed in the mirror's refs/heads, which is the history
        // the next task clones from and the base every review compares against.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        final TaskToken token = TaskToken.mint();
        final GitHttpServer server = new GitHttpServer(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), mirror, token,
                new GitSubprocess(), GitGate.INCOMING + "task-1");
        server.start();
        try (server) {
            git(root, "init", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            makeCommit("agent.txt", "written by the agent");

            // Refused in git's own words, which an agent reads and corrects: a bare 403 read to Claude Code as "an
            // authentication issue on the git server", and it gave up (measured 2026-10-01).
            assertThatThrownBy(() -> git(work, "push", url(server), "HEAD:refs/heads/main"))
                    .as("a branch of the mirror").hasMessageContaining("remote rejected")
                    .hasMessageContaining("this task's work goes to " + GitGate.INCOMING + "task-1")
                    .hasMessageContaining("remote: ").hasMessageContaining("push with 'git push sokar'")
                    .hasMessageNotContaining("403");
            assertThatThrownBy(() -> git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-2"))
                    .as("another task's ref").hasMessageContaining("remote rejected");
            git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-1");
            // Its rescue, beside it: pushed through this same gate when the task is removed with work it never
            // handed back. Refused until 2026-09-30, so every rescue failed.
            git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-1" + GitGate.RESCUED);
            assertThatThrownBy(() -> git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + "task-2"
                    + GitGate.RESCUED)).as("another task's rescue").hasMessageContaining("remote rejected");
        }

        assertThat(gate.resolves("refs/heads/main")).isFalse();
        assertThat(gate.resolves(GitGate.INCOMING + "task-2")).isFalse();
        assertThat(gate.log("task-1", null)).contains("add agent.txt");
    }

    @Test
    void readsTheRefsAPushWouldUpdateAndRefusesWhatItCannotRead() {
        final String commands = pkt("0".repeat(40) + " " + "a".repeat(40) + " refs/sokar/incoming/t\0report-status\n")
                + pkt("b".repeat(40) + " " + "c".repeat(40) + " refs/heads/main\n") + "0000PACK";

        assertThat(GitHttpServer.updatedRefs(commands.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .containsExactly("refs/sokar/incoming/t", "refs/heads/main");
        assertThatThrownBy(() -> GitHttpServer.updatedRefs("00".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isInstanceOf(GateException.class);
        assertThatThrownBy(() -> GitHttpServer.updatedRefs((pkt("push-cert\0x\n") + "0000")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(GateException.class);
    }

    private static String pkt(String payload) {
        return String.format("%04x", payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 4) + payload;
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

    /**
     * A runner whose git reads this test's own global configuration, so a signing key set up here - and
     * nothing of the machine's - is what signs.
     */
    private CommandRunner withGitConfig(Path config) {
        return command -> runner.run(command.withEnvironment(Map.of("GIT_CONFIG_GLOBAL", config.toString(),
                "GIT_CONFIG_NOSYSTEM", "1", "GIT_TERMINAL_PROMPT", "0")));
    }

    private Path upstreamWithMain() throws IOException {
        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());
        final Path seed = root.resolve("seed");
        git(root, "init", "--initial-branch=main", seed.toString());
        Files.writeString(seed.resolve("project.yml"), "project: {}\n");
        git(seed, "add", "project.yml");
        git(seed, "commit", "-m", "the project");
        git(seed, "push", upstream.toString(), "HEAD:refs/heads/main");
        return upstream;
    }

    private void pendingEnrolment(GitGate gate) throws IOException {
        git(root, "clone", "--quiet", mirror.toString(), work.toString());
        git(work, "fetch", "--quiet", root.resolve("upstream.git").toString(), "main");
        git(work, "checkout", "--quiet", "-B", "enrol", "FETCH_HEAD");
        makeCommit("allowed_signers", "sokar@second ssh-ed25519 AAAA\n");
        git(work, "push", "--quiet", mirror.toString(), "HEAD:" + GitGate.INCOMING + "enroll-second");
        assertThat(gate.pending()).containsExactly("enroll-second");
    }

    @Test
    void approvingSignedMergesTheWorkAsACommitSignedByThePersonsOwnKey() throws IOException {

        // What a machine's enrolment needs: every machine accepts a configuration only signed, and the key is
        // a person's. Git signs with the key the person's own git names; the gate never holds it.
        final Path upstream = upstreamWithMain();
        final Path key = root.resolve("signing");
        runner.runOrFail(Command.of("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "person", "-f",
                key.toString()));
        final Path config = Files.writeString(root.resolve("gitconfig"), "[user]\n\tname = Person\n"
                + "\temail = person@example.org\n\tsigningkey = " + key + ".pub\n[gpg]\n\tformat = ssh\n");
        final GitGate gate = new GitGate(withGitConfig(config), mirror, GateMode.GATEKEEPING, upstream.toString());
        gate.initialize();
        pendingEnrolment(gate);

        gate.approveSigned("enroll-second", "main", root.resolve("merge"));

        final String log = runner.runOrFail(Command.of("git", "--git-dir", upstream.toString(), "log", "-1",
                "--format=%s %P", "main")).standardOutput();
        assertThat(log).startsWith("Merge enroll-second, reviewed and signed").as("a merge: two parents")
                .matches("(?s).* \\S+ \\S+\\s*");
        assertThat(runner.runOrFail(Command.of("git", "--git-dir", upstream.toString(), "cat-file", "-p", "main"))
                .standardOutput()).contains("-----BEGIN SSH SIGNATURE-----");
        assertThat(gate.pending()).isEmpty();
    }

    @Test
    void approvingSignedWithNoSigningKeyPushesNothing() throws IOException {
        final Path upstream = upstreamWithMain();
        final Path config = Files.writeString(root.resolve("gitconfig"),
                "[user]\n\tname = Person\n\temail = person@example.org\n");
        final GitGate gate = new GitGate(withGitConfig(config), mirror, GateMode.GATEKEEPING, upstream.toString());
        gate.initialize();
        pendingEnrolment(gate);
        final String before = runner.runOrFail(Command.of("git", "--git-dir", upstream.toString(), "rev-parse",
                "main")).standardOutput();

        assertThatThrownBy(() -> gate.approveSigned("enroll-second", "main", root.resolve("merge")))
                .isInstanceOf(GateException.class).hasMessageContaining("user.signingkey");
        assertThat(runner.runOrFail(Command.of("git", "--git-dir", upstream.toString(), "rev-parse", "main"))
                .standardOutput()).as("nothing reached the upstream").isEqualTo(before);
        assertThat(gate.pending()).as("still waiting for a person").containsExactly("enroll-second");
    }

    @Test
    void workMergedElsewhereIsBundledOutAndClearedOnlyOnceTheUpstreamHasIt() throws IOException {

        // The interface's way: the reviewed work leaves as a bundle, a person merges and signs it on their own
        // computer and pushes it, and the gate clears it once - and only once - the upstream's branch holds it.
        final Path upstream = upstreamWithMain();
        final GitGate gate = gate(GateMode.GATEKEEPING, upstream.toString());
        pendingEnrolment(gate);

        final Path bundle = root.resolve("pending.bundle");
        assertThat(gate.bundle("enroll-second", "main", bundle)).isPositive().isLessThan(GitGate.BUNDLE_LIMIT);
        final Path person = root.resolve("person");
        git(root, "clone", "--quiet", upstream.toString(), person.toString());
        git(person, "fetch", "--quiet", bundle.toString(), GitGate.INCOMING + "enroll-second");

        final GitGate.Landing before = gate.landed("enroll-second", "main");
        assertThat(before.landed()).isFalse();
        assertThat(before.detail()).contains("not on the upstream's main yet");
        assertThat(gate.pending()).containsExactly("enroll-second");

        git(person, "merge", "--quiet", "--no-ff", "-m", "merged on the person's computer", "FETCH_HEAD");
        git(person, "push", "--quiet", "origin", "HEAD:main");

        final GitGate.Landing after = gate.landed("enroll-second", "main");
        assertThat(after.landed()).isTrue();
        assertThat(after.detail()).isEmpty();
        assertThat(gate.pending()).as("cleared, never rejected").isEmpty();
    }

    @Test
    void aFetchWithEnoughHistoryToBeCompressedIsServed() throws IOException {

        // Measured on 2026-09-30 after a finding about compressed bodies elsewhere: git compresses a fetch's
        // negotiation (Content-Encoding: gzip) once it is larger than a kilobyte - a clone with a few dozen
        // commits of its own. The gate must serve that fetch, not hand git a body it cannot read.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        final TaskToken token = TaskToken.mint();
        try (GitHttpServer server = serve(token)) {
            git(root, "init", "--initial-branch=main", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            for (int i = 0; i < 60; i++) {
                makeCommit("file-" + i + ".txt", "content " + i);
            }
            git(work, "push", "--quiet", url(server), "HEAD:" + GitGate.INCOMING + "task-1");
            final Path clone = root.resolve("clone");
            git(root, "-c", "http.extraHeader=Authorization: " + token.basicAuthorization(), "clone", "--quiet",
                    url(server), clone.toString());
            git(clone, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            git(clone, "fetch", "--quiet", "origin", GitGate.INCOMING + "task-1");
            git(clone, "checkout", "--quiet", "-b", "local", "FETCH_HEAD");
            // Commits the gate has never seen: each is a 'have' the fetch reports until the gate answers.
            for (int i = 0; i < 60; i++) {
                java.nio.file.Files.writeString(clone.resolve("local-" + i + ".txt"), "local " + i);
                git(clone, "add", "local-" + i + ".txt");
                git(clone, "commit", "--quiet", "-m", "local " + i);
            }
            makeCommit("one-more.txt", "one more");
            git(work, "push", "--quiet", url(server), "HEAD:" + GitGate.INCOMING + "task-1");

            // Sixty haves: well over the kilobyte at which git compresses the negotiation.
            final CommandResult fetched = runner.runOrFail(new Command(List.of("git", "fetch", "origin",
                    GitGate.INCOMING + "task-1"), clone, Map.of("GIT_TRACE_CURL", "1", "GIT_TRACE_CURL_NO_DATA", "1",
                    "GIT_TERMINAL_PROMPT", "0"), null));
            assertThat(fetched.exitCode()).isZero();
            assertThat(fetched.standardError()).as("git really compressed it, or this proves nothing")
                    .containsIgnoringCase("Content-Encoding: gzip");
        }
        assertThat(gate.pending()).containsExactly("task-1");
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

    private void pushAs(String name) throws IOException {
        final TaskToken token = TaskToken.mint();
        try (GitHttpServer server = serve(token)) {
            git(root, "init", "--initial-branch=main", work.toString());
            git(work, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());
            makeCommit(name + ".txt", "written by the agent");
            git(work, "push", url(server), "HEAD:" + GitGate.INCOMING + name);
        }
    }

    @Test
    void showsHowLongEachPushHasBeenWaiting() throws IOException {

        // Review lag is the number that says whether the gate is working. A queue nobody reads
        // turns into bulk approval without reading, and the review was theatre.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushAs("task-1");

        final var pending = gate.pendingDetail();

        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().name()).isEqualTo("task-1");
        assertThat(pending.getFirst().subject()).isEqualTo("add task-1.txt");
        assertThat(pending.getFirst().commit()).isNotBlank();
        assertThat(pending.getFirst().lag(java.time.Instant.now()).isNegative()).isFalse();
        assertThat(pending.getFirst().lagText(java.time.Instant.now())).endsWith("m");
    }

    @Test
    void ordersPendingPushesOldestFirst() throws IOException {

        // The oldest is the one most likely to have been forgotten.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushAs("task-1");
        pushAs("task-2");

        assertThat(gate.pendingDetail()).extracting(PendingPush::name)
                .containsExactly("task-1", "task-2");
    }

    @Test
    void backsUpAndRestoresTheMirror() throws IOException {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushAs("task-1");

        final Path bundle = root.resolve("backup/mirror.bundle");
        gate.backup(bundle);

        assertThat(bundle).exists();
        assertThat(gate.verifyBackup(bundle)).isTrue();

        final Path elsewhere = root.resolve("restored.git");
        final GitGate restored = new GitGate(runner, elsewhere, GateMode.GATEKEEPING, null);
        restored.restore(bundle);

        // The pending push has to survive: it is exactly the work an operator restoring a backup
        // is least able to reconstruct.
        assertThat(restored.pending()).containsExactly("task-1");
    }

    @Test
    void refusesToRestoreOverAnExistingMirror() throws IOException {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushAs("task-1");
        final Path bundle = root.resolve("mirror.bundle");
        gate.backup(bundle);

        // Restoring in place would silently discard whatever has been pushed since the backup.
        assertThatThrownBy(() -> gate.restore(bundle))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("Move it aside first");
    }

    @Test
    void refusesToRestoreSomethingThatIsNotABundle() throws IOException {

        final Path notABundle = root.resolve("junk.bundle");
        Files.writeString(notABundle, "definitely not a git bundle");

        final Path fresh = root.resolve("fresh.git");
        final GitGate gate = new GitGate(runner, fresh, GateMode.GATEKEEPING, null);

        assertThatThrownBy(() -> gate.restore(notABundle))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("not a usable git bundle");

        // The half-made repository must not survive, or the next restore reports that a mirror
        // already exists when what exists is the wreckage of this attempt.
        assertThat(fresh.resolve("objects")).doesNotExist();
    }

    @Test
    void verifyingWithoutARepositoryIsRefusedRatherThanAnsweredWrongly() throws IOException {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushAs("task-1");
        final Path bundle = root.resolve("mirror.bundle");
        gate.backup(bundle);

        // git needs a repository to check a bundle's prerequisites against, so answering this
        // from nowhere would be a guess. Whether the surrounding directory happens to be a git
        // repository must not change the result.
        final GitGate nowhere = new GitGate(runner, root.resolve("absent.git"), GateMode.GATEKEEPING, null);

        assertThatThrownBy(() -> nowhere.verifyBackup(bundle))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("without a repository");
    }

    @Test
    void saysSoRatherThanFailingWhenThereIsNothingToBackUp() {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);

        assertThatThrownBy(() -> gate.backup(root.resolve("empty.bundle")))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("nothing to back up");
    }

    private String url(GitHttpServer server) {
        return "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                + ":" + server.port() + "/mirror.git";
    }

    @Test
    void seedsFromOneRepositoryAndStillForwardsToAnother() {

        // Why seed and upstream are separate fields: a mirror seeded from a checkout on this
        // machine must never become the place an approved push is sent.
        final Path seed = root.resolve("seed");
        git(root, "init", "--initial-branch=main", seed.toString());
        runner.runOrFail(new Command(List.of("git", "commit", "--allow-empty", "-m", "seeded"),
                seed, Map.of("GIT_AUTHOR_NAME", "Test", "GIT_AUTHOR_EMAIL", "test@example.com",
                        "GIT_COMMITTER_NAME", "Test", "GIT_COMMITTER_EMAIL", "test@example.com"),
                null));

        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());

        final GitGate gate = new GitGate(runner, mirror, GateMode.GATEKEEPING,
                upstream.toString(), seed.toString());
        gate.initialize();

        assertThat(gate.resolves("refs/heads/main")).isTrue();
        assertThat(runner.runOrFail(Command.of("git", "--git-dir", mirror.toString(),
                "log", "--oneline", "main")).standardOutput()).contains("seeded");
        // The seed is a working checkout, and nothing may be pushed into it.
        assertThat(gate.seededFrom()).isEqualTo(seed.toString());
    }

    @Test
    void reportsThatAnEmptyMirrorCameFromNowhere() {

        // The negative case: a mirror created empty has no origin, and inventing one would make
        // the report worse than absent.
        final GitGate gate = new GitGate(runner, mirror, GateMode.GATEKEEPING, null);
        gate.initialize();

        assertThat(gate.seededFrom()).isNull();
    }

    @Test
    void seedsFromTheUpstreamWhenNoSeparateSeedIsGiven() {

        // The four-argument constructor has to keep behaving as it always did.
        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());

        new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString()).initialize();

        assertThat(runner.runOrFail(Command.of("git", "--git-dir", mirror.toString(),
                "config", "--get", "remote.origin.url")).standardOutput().strip())
                .isEqualTo(upstream.toString());
    }

    @Test
    void bringsTheMirrorsBranchesUpToTheUpstreamBeforeATaskStartsFromThemAndKeepsWhatWaits() throws Exception {

        // Approved work goes to the upstream, never into the mirror: without this the next task started from the old
        // base, and its forward was refused as no fast-forward (found on a real repository, 2026-10-01).
        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());
        git(root, "init", "--initial-branch=main", work.toString());
        makeCommit("first.txt", "first");
        git(work, "push", upstream.toString(), "HEAD:main");

        final GitGate gate = new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString());
        gate.initialize();
        git(work, "push", mirror.toString(), "HEAD:" + GitGate.INCOMING + "waiting");
        makeCommit("second.txt", "approved and forwarded elsewhere");
        git(work, "push", upstream.toString(), "HEAD:main");
        final String forwarded = runner.runOrFail(Command.of("git", "-C", work.toString(), "rev-parse", "HEAD"))
                .standardOutput().strip();

        assertThat(gate.refresh()).isEmpty();

        assertThat(runner.runOrFail(Command.of("git", "--git-dir", mirror.toString(), "rev-parse", "refs/heads/main"))
                .standardOutput().strip()).isEqualTo(forwarded);
        assertThat(gate.resolves(GitGate.INCOMING + "waiting")).as("what waits for review is kept").isTrue();
    }

    @Test
    void anOfflineMirrorIsNeverBroughtUpToAnything() throws Exception {
        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());
        git(root, "init", "--initial-branch=main", work.toString());
        makeCommit("first.txt", "first");
        git(work, "push", upstream.toString(), "HEAD:main");
        final GitGate gate = new GitGate(runner, mirror, GateMode.OFFLINE, upstream.toString());
        gate.initialize();
        final String before = runner.runOrFail(Command.of("git", "--git-dir", mirror.toString(), "rev-parse",
                "refs/heads/main")).standardOutput().strip();
        makeCommit("second.txt", "elsewhere");
        git(work, "push", upstream.toString(), "HEAD:main");

        assertThat(gate.refresh()).isEmpty();
        assertThat(runner.runOrFail(Command.of("git", "--git-dir", mirror.toString(), "rev-parse", "refs/heads/main"))
                .standardOutput().strip()).isEqualTo(before);
    }

    @Test
    void seedsThroughTheLendingAsEveryOtherCallToTheUpstreamDoes() {

        // An ssh seed needs the machine's host-key policy and its known hosts, which only the lending gives. The
        // first clone went without, so a host this machine had vouched for failed and the task had no gate.
        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());
        final java.util.List<String> asked = new java.util.ArrayList<>();
        final boolean[] closed = { false };
        final GitCredentials lending = url -> {
            asked.add(url);
            return new GitCredentials.Lease() {

                @Override
                public java.util.Map<String, String> environment() {
                    return java.util.Map.of("GIT_CONFIG_COUNT", "1", "GIT_CONFIG_KEY_0", "sokar.lent",
                            "GIT_CONFIG_VALUE_0", "yes");
                }

                @Override
                public java.util.List<String> arguments() {
                    return java.util.List.of();
                }

                @Override
                public void close() {
                    closed[0] = true;
                }
            };
        };

        new GitGate(runner, mirror, GateMode.GATEKEEPING, upstream.toString()).using(lending).initialize();

        assertThat(asked).containsExactly(upstream.toString());
        assertThat(closed[0]).isTrue();
        assertThat(runner.runOrFail(Command.of("git", "--git-dir", mirror.toString(),
                "config", "--get", "remote.origin.url")).standardOutput().strip()).isEqualTo(upstream.toString());
    }

    /**
     * Puts work in the mirror the direct way. What these tests are about is the checkout, so the
     * push path - which has tests of its own above - is not part of them.
     */
    private void pushWork(String name, String file) throws Exception {
        git(root, "init", "--initial-branch=main", work.toString());
        makeCommit(file, "written by the agent");
        git(work, "push", mirror.toString(), "HEAD:" + GitGate.INCOMING + name);
    }

    @Test
    void checkoutMaterialisesTheWorkSomebodyCanOpen() throws Exception {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushWork("shell", "one.txt");

        final Path opened = root.resolve("opened");
        gate.checkout("shell", opened);

        assertThat(opened.resolve("one.txt")).exists();
        assertThat(opened.resolve(".git")).isDirectory();
    }

    @Test
    void aCheckoutHasNowhereToPushExceptBackToTheGate() throws Exception {

        // Not a restriction that can be forgotten - an address that is not there. The unsafe path
        // is "fetch it into your own checkout and push", and this copy simply has no upstream to
        // push to.
        //
        // The gate is built WITH an upstream on purpose. Written against a gate that had none,
        // this test passed while a mutation added the upstream as a remote - it was asserting
        // against a fixture that could not have failed.
        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", upstream.toString());
        final GitGate gate = gate(GateMode.GATEKEEPING, upstream.toString());
        pushWork("shell", "one.txt");
        final Path opened = root.resolve("opened");
        gate.checkout("shell", opened);

        final String remotes = git(opened, "remote", "-v").standardOutput();
        assertThat(remotes).contains("sokar").contains(mirror.toString());
        assertThat(remotes.lines().map(line -> line.split("\\s+")[0]).distinct())
                .as("the mirror and nothing else").containsExactly("sokar");
    }

    @Test
    void noHookRunsInACheckout() throws Exception {

        // An agent's work is about to be opened in an editor that runs git on it, and a hook is
        // code that runs without being read. Proved by putting one where git would find it and
        // showing it does not fire.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushWork("shell", "one.txt");
        final Path opened = root.resolve("opened");
        gate.checkout("shell", opened);

        final Path hooks = Path.of(git(opened, "config", "core.hooksPath")
                .standardOutput().strip());
        assertThat(hooks).as("hooks come from a directory of our own").isDirectory();
        assertThat(hooks.toFile().list()).as("and it is empty").isEmpty();
    }

    @Test
    void aCheckoutIsDetachedSoNothingLooksLikeWorkToContinue() throws Exception {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushWork("shell", "one.txt");
        final Path opened = root.resolve("opened");
        gate.checkout("shell", opened);

        // Asked without runOrFail: a non-zero exit IS the assertion here, and the helper would
        // turn the thing being proved into a thrown error.
        assertThat(runner.run(new Command(List.of("git", "symbolic-ref", "--quiet", "HEAD"),
                opened, Map.of(), null)).successful())
                .as("HEAD names no branch").isFalse();
    }

    @Test
    void refusesToWriteIntoSomethingThatIsAlreadyThere() throws Exception {

        // Mixing an agent's work into a directory somebody already has is the accident this whole
        // requirement is about, arriving through the command meant to prevent it.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushWork("shell", "one.txt");
        final Path occupied = root.resolve("occupied");
        java.nio.file.Files.createDirectories(occupied);

        assertThatThrownBy(() -> gate.checkout("shell", occupied))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void refusesARefNobodyPushed() throws Exception {

        final GitGate gate = gate(GateMode.GATEKEEPING, null);

        assertThatThrownBy(() -> gate.checkout("never-pushed", root.resolve("opened")))
                .isInstanceOf(GateException.class);
    }

    @Test
    void refusesToDiscardWorkTheUpstreamAlreadyHas() throws Exception {

        // The dangerous one of the three things a hand push breaks. approve pushes AND deletes
        // the ref; a hand push does the first only, so the ref stays - and somebody tidying up
        // then writes down "discarded" about code that is live.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushWork("shell", "one.txt");

        assertThatThrownBy(() -> gate.reject("shell", true, false))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("already on the upstream")
                .hasMessageContaining("force");

        assertThat(gate.pending()).as("and it is still there").containsExactly("shell");
    }

    @Test
    void discardsItAnywayWhenSomebodySaysForce() throws Exception {

        // A person who means it can still do it - the same property the whole requirement rests
        // on. What must not happen is doing it without being told.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushWork("shell", "one.txt");

        gate.reject("shell", true, true);

        assertThat(gate.pending()).isEmpty();
    }

    @Test
    void discardsWorkNobodyPushedUpstreamWithoutBeingAskedTwice() throws Exception {

        // The ordinary case has to stay ordinary. A refusal that fired on everything would be
        // trained past within a day.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushWork("shell", "one.txt");

        gate.reject("shell", false, false);

        assertThat(gate.pending()).isEmpty();
    }

    @Test
    void whatIsApprovedIsTheCommitThatWasReviewedNeverOneThatCameAfter() throws Exception {

        // The task's gate stays up while a person reads: a push after the review reached the upstream unseen, and
        // one landing between the forward and the clean-up was deleted.
        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());
        final GitGate gate = gate(GateMode.GATEKEEPING, upstream.toString());
        pushWork("task-1", "reviewed.txt");
        final String reviewed = git(mirror, "rev-parse", GitGate.INCOMING + "task-1").standardOutput().strip();
        makeCommit("after.txt", "pushed after the review");
        git(work, "push", mirror.toString(), "HEAD:" + GitGate.INCOMING + "task-1");

        assertThatThrownBy(() -> gate.approve("task-1", "main", reviewed))
                .isInstanceOf(GateException.MovedSinceReview.class).hasMessageContaining("moved");
        assertThat(runner.runOrFail(Command.of("git", "--git-dir", upstream.toString(), "for-each-ref"))
                .standardOutput()).as("nothing went").isEmpty();

        final String now = git(mirror, "rev-parse", GitGate.INCOMING + "task-1").standardOutput().strip();
        gate.approve("task-1", "main", now);

        assertThat(runner.runOrFail(Command.of("git", "--git-dir", upstream.toString(), "rev-parse", "main"))
                .standardOutput().strip()).isEqualTo(now);
        assertThat(gate.pending()).isEmpty();
    }

    @Test
    void aTaskSeesAndFetchesItsOwnWorkAndNoOtherTasks() throws Exception {

        // Every task's gate served the whole shared mirror: one agent listed another's ref and fetched its unreviewed
        // work.
        gate(GateMode.GATEKEEPING, null);
        pushWork("task-2", "theirs.txt");
        pushWork("task-10", "a-longer-name.txt");
        final TaskToken token = TaskToken.mint();
        try (GitHttpServer server = new GitHttpServer(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
                mirror, token, new GitSubprocess(), GitGate.INCOMING + "task-1")) {
            server.start();
            final Path other = root.resolve("other");
            git(root, "init", "--initial-branch=main", other.toString());
            git(other, "config", "http.extraHeader", "Authorization: " + token.basicAuthorization());

            assertThat(git(other, "ls-remote", url(server)).standardOutput()).doesNotContain("task-2")
                    .as("a name that only begins like its own").doesNotContain("task-10");
            assertThatThrownBy(() -> git(other, "fetch", url(server), GitGate.INCOMING + "task-2"))
                    .as("not by name either");
        }
    }

    @Test
    void aRefNameIsReadAsGitReadsItWithNothingTrimmedButTheLineEnd() throws Exception {

        // Trimmed as Java trims, a name ending in U+3000 compared equal to the task's own ref and git made a second
        // ref of it, listed beside the real one under the same name.
        final byte[] line = ("0".repeat(40) + " " + "a".repeat(40) + " refs/sokar/incoming/task-1\u3000\0report-status\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final java.io.ByteArrayOutputStream request = new java.io.ByteArrayOutputStream();
        request.write(String.format("%04x", line.length + 4).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        request.write(line);
        request.write("0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        assertThat(GitHttpServer.updatedRefs(request.toByteArray())).containsExactly("refs/sokar/incoming/task-1\u3000");
    }

    @Test
    void aRefThatIsNoCommitIsListedAsSuchAndTheOthersStillAre() throws Exception {

        // An annotated tag or a blob pushed to an incoming ref - receive-pack refuses those only under refs/heads -
        // made the listing throw, and every task of the project lost its review queue.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        pushWork("task-1", "work.txt");
        final Path blob = root.resolve("blob.txt");
        Files.writeString(blob, "not a commit");
        final String object = git(mirror, "hash-object", "-w", blob.toString()).standardOutput().strip();
        git(mirror, "update-ref", GitGate.INCOMING + "odd", object);

        final List<PendingPush> pending = gate.pendingDetail();

        assertThat(pending).extracting(PendingPush::name).containsExactlyInAnyOrder("task-1", "odd");
        assertThat(pending).filteredOn(push -> push.name().equals("odd")).singleElement()
                .satisfies(push -> assertThat(push.subject()).contains("not a commit"));
    }

    @Test
    void aWorkflowWhoseNameGitWouldQuoteIsStillDangerous() throws Exception {

        // Read without -z, git quoted a path with a byte beyond ASCII or a '"', and the quoted name matched no
        // dangerous place: a workflow ranked ordinary.
        final GitGate gate = gate(GateMode.GATEKEEPING, null);
        git(root, "init", "--initial-branch=main", work.toString());
        Files.createDirectories(work.resolve(".github/workflows"));
        Files.writeString(work.resolve(".github/workflows/b\u00fcild.yml"), "on: push\n");
        Files.writeString(work.resolve(".github/workflows/a\"b.yml"), "on: push\n");
        git(work, "add", ".");
        git(work, "commit", "-m", "workflows");
        git(work, "push", mirror.toString(), "HEAD:" + GitGate.INCOMING + "task-1");

        assertThat(gate.rankedReview("task-1", null).files()).extracting(ReviewRanking.File::path,
                ReviewRanking.File::rank).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple(".github/workflows/b\u00fcild.yml", ReviewRanking.Rank.DANGEROUS),
                org.assertj.core.groups.Tuple.tuple(".github/workflows/a\"b.yml", ReviewRanking.Rank.DANGEROUS));
    }

    @Test
    void aRefusedNameReachesTheLogAsOneLineWithNothingATerminalActsOn() {

        // Printed as the agent sent it, a name could write lines of its own into the gate log, or escape sequences
        // into the terminal of whoever follows it.
        assertThat(GitHttpServer.printable("refs/heads/x\nrefused: all is well\u001b[2J"))
                .isEqualTo("refs/heads/x\\nrefused: all is well\\x1b[2J");
    }

    @Test
    void aRequestBodyBeyondItsLimitIsRefusedBeforeItIsHeld() {

        // Read whole, an agent could stream a body of many gigabytes and run its own gate out of memory.
        assertThatThrownBy(() -> GitHttpServer.bounded(new java.io.ByteArrayInputStream(new byte[11]), 10))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("10");
        assertThat(uncheckedBounded(new byte[10], 10)).hasSize(10);
    }

    private static byte[] uncheckedBounded(byte[] body, long limit) {
        try {
            return GitHttpServer.bounded(new java.io.ByteArrayInputStream(body), limit);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void anUpstreamThatReadsAsAnOptionIsNeverTakenForOne() throws Exception {

        // Written into the project file, '--upload-pack=<command>' reached 'git fetch' as an option and ran on the host.
        // The project file refuses it; the gate never hands an address to git where an option could be.
        final Path marker = root.resolve("owned");
        final GitGate gate = new GitGate(runner, mirror, GateMode.GATEKEEPING, "--upload-pack=touch " + marker + ";false");

        try {
            gate.initialize();
        } catch (GateException ex) {
            // Refused as an address that is not there, which is the point.
        }
        try {
            git(root, "init", "--bare", mirror.toString());
            gate.refresh();
        } catch (GateException | org.fuin.sokar.core.process.CommandException ex) {
            // The same.
        }

        assertThat(marker).doesNotExist();
    }

    @Test
    void theReviewedCommitIsNamedInFullBecauseAShortOneCanBeMatchedByAnother() throws Exception {

        // Compared as a prefix, the seven characters a listing shows could be met by another commit the agent ground
        // out - about 2^28 tries - and pushed while the person read.
        final Path upstream = root.resolve("upstream.git");
        git(root, "init", "--bare", "--initial-branch=main", upstream.toString());
        final GitGate gate = gate(GateMode.GATEKEEPING, upstream.toString());
        pushWork("task-1", "reviewed.txt");
        final String full = git(mirror, "rev-parse", GitGate.INCOMING + "task-1").standardOutput().strip();

        assertThatThrownBy(() -> gate.approve("task-1", "main", full.substring(0, 7)))
                .isInstanceOf(GateException.class).hasMessageContaining("full");
        gate.approve("task-1", "main", full);
        assertThat(gate.pending()).isEmpty();
    }
}
