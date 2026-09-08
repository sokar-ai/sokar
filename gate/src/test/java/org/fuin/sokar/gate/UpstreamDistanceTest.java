package org.fuin.sokar.gate;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Tests for {@link UpstreamDistance}, against real repositories.
 * <p>
 * Faking git here would test that the arguments match what was written down. What has to be true
 * is that a real fetch into a real bare mirror counts what a person would count and moves nothing
 * the gate depends on - and only a real one can say.
 */
class UpstreamDistanceTest {

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(60));

    private CommandResultOf git(Path directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return new CommandResultOf(runner.runOrFail(new Command(all, directory,
                Map.of("GIT_AUTHOR_NAME", "Test", "GIT_AUTHOR_EMAIL", "test@example.com",
                        "GIT_COMMITTER_NAME", "Test", "GIT_COMMITTER_EMAIL", "test@example.com",
                        "GIT_TERMINAL_PROMPT", "0"),
                null)).standardOutput());
    }

    /** Only so the helper reads as a statement rather than as a discarded value. */
    private record CommandResultOf(String output) { }

    /**
     * Builds an upstream with one commit on it and returns its path.
     * <p>
     * <strong>The initial branch is named, not inherited.</strong> Without it {@code git init}
     * takes the branch name from the machine's own configuration, and the upstream ends up with
     * HEAD pointing at a branch that was never created - which makes {@code git fetch origin}
     * fail with "couldn't find remote ref HEAD", because with no refspec that is exactly what it
     * asks for. This test passed on a machine with {@code init.defaultBranch=main} and failed on
     * CI, which has none: the fixture depended on whoever ran it.
     */
    private Path upstreamWithOneCommit(Path root) throws Exception {
        final Path upstream = root.resolve("upstream.git");
        final Path work = root.resolve("work");
        git(root, "init", "--quiet", "--bare", "--initial-branch=main", upstream.toString());
        git(root, "init", "--quiet", "--initial-branch=main", work.toString());
        java.nio.file.Files.writeString(work.resolve("one.txt"), "one\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "one");
        git(work, "branch", "-M", "main");
        git(work, "push", "--quiet", upstream.toString(), "main");
        return upstream;
    }

    @Test
    void measuresAnUpstreamWhoseDefaultBranchIsNotMain(@TempDir Path root) throws Exception {

        // The mirror's HEAD and the upstream's HEAD are what get compared, so a project whose
        // default branch is called something else has to work exactly the same. Naming 'main'
        // anywhere in the measurement would answer 0 for every repository that does not use it.
        final Path upstream = root.resolve("upstream.git");
        final Path work = root.resolve("work");
        git(root, "init", "--quiet", "--bare", "--initial-branch=trunk", upstream.toString());
        git(root, "init", "--quiet", "--initial-branch=trunk", work.toString());
        java.nio.file.Files.writeString(work.resolve("one.txt"), "one\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "one");
        git(work, "push", "--quiet", upstream.toString(), "trunk");

        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());
        java.nio.file.Files.writeString(work.resolve("two.txt"), "two\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "two");
        git(work, "push", "--quiet", upstream.toString(), "trunk");

        final UpstreamDistance.Distance distance =
                UpstreamDistance.measure(runner, mirror, GateMode.GATEKEEPING);

        assertThat(distance.reason()).as("git said: %s", distance.detail())
                .isEqualTo(UpstreamDistance.Reason.MEASURED);
        assertThat(distance.behind()).isEqualTo(1);
    }

    private void commitTo(Path root, Path upstream, String name) throws Exception {
        final Path work = root.resolve("work");
        java.nio.file.Files.writeString(work.resolve(name + ".txt"), name + "\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", name);
        git(work, "push", "--quiet", upstream.toString(), "main");
    }

    @Test
    void countsWhatTheUpstreamHasAndTheMirrorDoesNot(@TempDir Path root) throws Exception {

        final Path upstream = upstreamWithOneCommit(root);
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());
        commitTo(root, upstream, "two");
        commitTo(root, upstream, "three");

        final UpstreamDistance.Distance distance =
                UpstreamDistance.measure(runner, mirror, GateMode.GATEKEEPING);

        // The detail is in the message on purpose: without it a failure here reads only
        // "expected MEASURED but was FAILED" and throws away git's own words, which is the one
        // thing that would explain it. That cost a reproduction round on this very test.
        assertThat(distance.reason()).as("git said: %s", distance.detail())
                .isEqualTo(UpstreamDistance.Reason.MEASURED);
        assertThat(distance.behind()).isEqualTo(2);
        assertThat(distance.measured()).isNotNull();
    }

    @Test
    void answersZeroForAMirrorThatIsUpToDate(@TempDir Path root) throws Exception {

        // Zero and "never checked" are the same number and different sentences, which is why the
        // reason travels with it rather than an interface inferring one from the other.
        final Path upstream = upstreamWithOneCommit(root);
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());

        final UpstreamDistance.Distance distance =
                UpstreamDistance.measure(runner, mirror, GateMode.GATEKEEPING);

        assertThat(distance.reason()).as("git said: %s", distance.detail())
                .isEqualTo(UpstreamDistance.Reason.MEASURED);
        assertThat(distance.behind()).isZero();
    }

    @Test
    void movesNoRefTheGateDependsOn(@TempDir Path root) throws Exception {

        // The reason this is safe to run on a timer. A bare clone has no fetch refspec - measured,
        // not assumed - so fetching writes FETCH_HEAD and leaves refs/heads alone. If it moved
        // them, measuring would silently change what the gate considers reviewed.
        final Path upstream = upstreamWithOneCommit(root);
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());
        final String before = git(mirror, "--git-dir", mirror.toString(),
                "for-each-ref", "--format=%(refname) %(objectname)").output();
        commitTo(root, upstream, "two");

        UpstreamDistance.measure(runner, mirror, GateMode.GATEKEEPING);

        assertThat(git(mirror, "--git-dir", mirror.toString(),
                "for-each-ref", "--format=%(refname) %(objectname)").output()).isEqualTo(before);
    }

    @Test
    void refusesToLookForAnOfflineProject(@TempDir Path root) throws Exception {

        // The class promises nothing leaves. Contacting the upstream to answer a question an
        // offline project can never act on would break that promise for nothing.
        final Path upstream = upstreamWithOneCommit(root);
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());
        commitTo(root, upstream, "two");

        final UpstreamDistance.Distance distance =
                UpstreamDistance.measure(runner, mirror, GateMode.OFFLINE);

        assertThat(distance.reason()).isEqualTo(UpstreamDistance.Reason.OFFLINE);
        assertThat(distance.measured()).isNull();
    }

    @Test
    void saysAMirrorWithNoUpstreamHasNothingToBeBehind(@TempDir Path root) throws Exception {

        // Created empty rather than cloned. "Behind" has no meaning here, as opposed to having one
        // nobody has measured - and an interface has different sentences for those.
        final Path mirror = root.resolve("mirror.git");
        git(root, "init", "--quiet", "--bare", mirror.toString());

        assertThat(UpstreamDistance.measure(runner, mirror, GateMode.GATEKEEPING).reason())
                .isEqualTo(UpstreamDistance.Reason.NO_UPSTREAM);
    }

    @Test
    void saysNothingHasLookedWhenThereIsNoMirrorYet(@TempDir Path root) {

        assertThat(UpstreamDistance.measure(runner, root.resolve("absent.git"), GateMode.GATEKEEPING)
                .reason()).isEqualTo(UpstreamDistance.Reason.NEVER_CHECKED);
    }

    @Test
    void reportsAnUnreachableUpstreamRatherThanThrowing(@TempDir Path root) throws Exception {

        // This runs on a timer over every project. One upstream that is down, renamed or behind a
        // credential nobody has must not stop the others being measured, and it must not surface
        // as a number.
        final Path upstream = upstreamWithOneCommit(root);
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());
        try (var walk = java.nio.file.Files.walk(upstream)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    java.nio.file.Files.delete(path);
                } catch (java.io.IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }

        final UpstreamDistance.Distance distance =
                UpstreamDistance.measure(runner, mirror, GateMode.GATEKEEPING);

        assertThat(distance.reason()).isEqualTo(UpstreamDistance.Reason.FAILED);
        assertThat(distance.detail()).isNotBlank();
        assertThat(distance.measured()).isNull();
    }

    @Test
    void findsWorkSomebodyPushedByHand(@TempDir Path root) throws Exception {

        // The whole reason for asking. 'approve' pushes AND deletes the ref; a hand push does the
        // first only, so the ref stays and the gate lists it as waiting for ever - and a 'reject'
        // afterwards records it as discarded while the code is upstream.
        final Path upstream = upstreamWithOneCommit(root);
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());

        // An agent pushes work into the mirror...
        final Path work = root.resolve("work");
        java.nio.file.Files.writeString(work.resolve("agent.txt"), "written by the agent\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "agent work");
        git(work, "push", "--quiet", mirror.toString(),
                "HEAD:" + GitGate.INCOMING + "shell");
        // ...and a person then pushes the same commit upstream themselves, past the gate.
        git(work, "push", "--quiet", upstream.toString(), "main");

        final UpstreamDistance.Distance distance =
                UpstreamDistance.measure(runner, mirror, GateMode.GATEKEEPING);

        assertThat(distance.alreadyUpstream()).containsExactly("shell");
    }

    @Test
    void workNobodyPushedIsNotReportedAsUpstream(@TempDir Path root) throws Exception {

        // The entry that really is waiting has to keep standing out. Clearing something that is
        // not there would be the dangerous direction of this mistake.
        final Path upstream = upstreamWithOneCommit(root);
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());

        final Path work = root.resolve("work");
        java.nio.file.Files.writeString(work.resolve("agent.txt"), "written by the agent\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "agent work");
        git(work, "push", "--quiet", mirror.toString(), "HEAD:" + GitGate.INCOMING + "shell");

        final UpstreamDistance.Distance distance =
                UpstreamDistance.measure(runner, mirror, GateMode.GATEKEEPING);

        assertThat(distance.alreadyUpstream()).isEmpty();
    }

    @Test
    void anOfflineProjectIsNotAskedAboutAtAll(@TempDir Path root) throws Exception {

        final Path upstream = upstreamWithOneCommit(root);
        final Path mirror = root.resolve("mirror.git");
        git(root, "clone", "--quiet", "--bare", upstream.toString(), mirror.toString());

        assertThat(UpstreamDistance.measure(runner, mirror, GateMode.OFFLINE).alreadyUpstream())
                .isEmpty();
    }
}
