package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link GateCheckCommand}, against real repositories and real pushes.
 * <p>
 * The question this command answers is "what would git send", and only git can say. A fake runner
 * would test that the arguments match what was written down, which is the part that was never in
 * doubt - the first version of {@code --not --all} was wrong about a first push, and no assertion
 * on arguments would have noticed.
 */
class GateCheckCommandTest {

    private static final String AGENT = "agent@sokar.invalid";

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(60));

    private String git(Path directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(new Command(all, directory,
                Map.of("GIT_TERMINAL_PROMPT", "0"), null)).standardOutput().strip();
    }

    /** Commits a file, under whichever address is given. */
    private String commit(Path work, String file, String email, String subject) throws Exception {
        Files.writeString(work.resolve(file), file + "\n");
        git(work, "add", ".");
        git(work, "-c", "user.name=Somebody", "-c", "user.email=" + email,
                "commit", "--quiet", "-m", subject);
        return git(work, "rev-parse", "HEAD");
    }

    private Path repository(Path root) {
        final Path work = root.resolve("work");
        git(root, "init", "--quiet", "--initial-branch=main", work.toString());
        return work;
    }

    /** The line git writes to a pre-push hook. */
    private String hookLine(String localSha, String remoteSha) {
        return "refs/heads/main " + localSha + " refs/heads/main " + remoteSha;
    }

    @Test
    void namesTheCommitsAnAgentWroteAndLeavesTheOthersAlone(@TempDir Path root) throws Exception {

        final Path work = repository(root);
        commit(work, "mine.txt", "me@example.com", "something I wrote");
        commit(work, "theirs.txt", AGENT, "something the agent wrote");
        final String head = git(work, "rev-parse", "HEAD");

        final var found = GateCheckCommand.offenders(runner, work,
                List.of(hookLine(head, GateCheckCommand.ABSENT)), Set.of(AGENT), null);

        assertThat(found).singleElement()
                .satisfies(offender -> assertThat(offender.subject())
                        .isEqualTo("something the agent wrote"));
    }

    @Test
    void aFirstPushOfABranchCountsEverythingTheFarSideHasNotGot(@TempDir Path root)
            throws Exception {

        // Forty zeros on the remote side is git saying "there is no such branch there yet". The
        // range form has nothing to subtract from, so the whole branch has to be walked instead -
        // and a check that got this wrong would wave through exactly the push that matters most,
        // the first one.
        final Path work = repository(root);
        commit(work, "one.txt", AGENT, "one");
        commit(work, "two.txt", AGENT, "two");
        final String head = git(work, "rev-parse", "HEAD");

        final var found = GateCheckCommand.offenders(runner, work,
                List.of(hookLine(head, GateCheckCommand.ABSENT)), Set.of(AGENT), null);

        assertThat(found).extracting(GateCheckCommand.Offender::subject)
                .containsExactly("two", "one");
    }

    @Test
    void aFirstPushIsNotCancelledOutByTheVeryRefBeingPushed(@TempDir Path root) throws Exception {

        // The regression this whole method was rewritten for. "--not --all" reads as "everything
        // no other ref has", but the ref being pushed is one of --all, so it subtracts itself and
        // answers nothing - a check that installs, runs, and never fires. Measured: 0 commits
        // where three are leaving.
        final Path work = repository(root);
        commit(work, "one.txt", AGENT, "one");
        final String head = git(work, "rev-parse", "HEAD");
        assertThat(git(work, "rev-list", head, "--not", "--all"))
                .as("the form that was wrong, kept here so the reason stays visible").isEmpty();

        final var found = GateCheckCommand.offenders(runner, work,
                List.of(hookLine(head, GateCheckCommand.ABSENT)), Set.of(AGENT), null);

        assertThat(found).hasSize(1);
    }

    @Test
    void aFirstPushIsMeasuredAgainstTheRemoteBeingPushedToAndNoOther(@TempDir Path root)
            throws Exception {

        // git tells a hook which remote it is pushing to, and taking that name is what keeps the
        // answer honest with more than one. Here origin already has the first commit and the fork
        // has nothing, so a push to the fork is sending both - and a check that subtracted every
        // remote instead of the named one would report one commit and wave the other through.
        // With a single remote the two forms agree, which is why this fixture has two.
        final Path work = repository(root);
        commit(work, "old.txt", AGENT, "already on origin");
        git(root, "init", "--quiet", "--bare", "--initial-branch=main",
                root.resolve("origin.git").toString());
        git(root, "init", "--quiet", "--bare", "--initial-branch=main",
                root.resolve("fork.git").toString());
        git(work, "remote", "add", "origin", root.resolve("origin.git").toString());
        git(work, "remote", "add", "fork", root.resolve("fork.git").toString());
        git(work, "push", "--quiet", "origin", "main");
        commit(work, "new.txt", AGENT, "on neither");
        final String head = git(work, "rev-parse", "HEAD");
        final List<String> push = List.of(hookLine(head, GateCheckCommand.ABSENT));

        final var toFork = GateCheckCommand.offenders(runner, work, push, Set.of(AGENT), "fork");
        final var toOrigin = GateCheckCommand.offenders(runner, work, push, Set.of(AGENT),
                "origin");

        assertThat(toFork).extracting(GateCheckCommand.Offender::subject)
                .containsExactly("on neither", "already on origin");
        assertThat(toOrigin).extracting(GateCheckCommand.Offender::subject)
                .containsExactly("on neither");
    }

    @Test
    void anOrdinaryPushCountsOnlyWhatIsNewSinceTheFarSide(@TempDir Path root) throws Exception {

        final Path work = repository(root);
        commit(work, "old.txt", AGENT, "already pushed");
        final String pushed = git(work, "rev-parse", "HEAD");
        commit(work, "new.txt", AGENT, "not pushed yet");
        final String head = git(work, "rev-parse", "HEAD");

        final var found = GateCheckCommand.offenders(runner, work,
                List.of(hookLine(head, pushed)), Set.of(AGENT), null);

        assertThat(found).extracting(GateCheckCommand.Offender::subject)
                .containsExactly("not pushed yet");
    }

    @Test
    void deletingABranchAsksGitNothingAtAll(@TempDir Path root) throws Exception {

        // A deletion has forty zeros on the local side. Asserting only that nothing is found
        // proves nothing: take the guard away and git fails on the zeros, the failure reads as
        // "no commits", and the test still passes. What the guard is for is that a deletion is
        // recognised rather than handed to git as a question it can only answer with an error -
        // so what is asserted is that no command is run.
        final Path work = repository(root);
        commit(work, "one.txt", AGENT, "one");
        final String head = git(work, "rev-parse", "HEAD");
        final List<List<String>> asked = new ArrayList<>();
        final CommandRunner recording = new CommandRunner() {
            @Override
            public org.fuin.sokar.core.process.CommandResult run(Command command) {
                asked.add(command.arguments());
                return runner.run(command);
            }
        };

        final var found = GateCheckCommand.offenders(recording, work,
                List.of(hookLine(GateCheckCommand.ABSENT, head)), Set.of(AGENT), null);

        assertThat(found).isEmpty();
        assertThat(asked).isEmpty();
    }

    @Test
    void anAuthorSurvivesARebaseSoTheCheckStillSeesIt(@TempDir Path root) throws Exception {

        // It was assumed an author is "easy to lose in a rebase", and proposed to rewrite commits at
        // approval time to carry a trailer instead. Measured here rather than assumed: a rebase
        // changes the committer and keeps the author, so the cheap signal is the durable one and
        // nothing has to be rewritten.
        final Path work = repository(root);
        commit(work, "base.txt", "me@example.com", "base");
        final String base = git(work, "rev-parse", "HEAD");
        git(work, "checkout", "--quiet", "-b", "side");
        commit(work, "side.txt", AGENT, "agent work");
        git(work, "checkout", "--quiet", "main");
        commit(work, "main.txt", "me@example.com", "moved on");
        git(work, "checkout", "--quiet", "side");
        git(work, "-c", "user.name=Me", "-c", "user.email=me@example.com", "rebase", "main");
        final String head = git(work, "rev-parse", "HEAD");

        assertThat(git(work, "show", "--no-patch", "--format=%ce", head))
                .as("the rebase did run and did change the committer")
                .isEqualTo("me@example.com");
        final var found = GateCheckCommand.offenders(runner, work,
                List.of(hookLine(head, base)), Set.of(AGENT), null);

        assertThat(found).extracting(GateCheckCommand.Offender::subject)
                .containsExactly("agent work");
    }

    @Test
    void twoRefsSharingHistoryNameACommitOnce(@TempDir Path root) throws Exception {

        // Pushing a branch and a tag of the same tip hands the hook two lines. Counting the
        // commit twice would report "2 commits" for one, and nothing on screen would let somebody
        // tell that from two real ones.
        final Path work = repository(root);
        commit(work, "one.txt", AGENT, "one");
        final String head = git(work, "rev-parse", "HEAD");

        final var found = GateCheckCommand.offenders(runner, work,
                List.of("refs/heads/main " + head + " refs/heads/main " + GateCheckCommand.ABSENT,
                        "refs/tags/v1 " + head + " refs/tags/v1 " + GateCheckCommand.ABSENT),
                Set.of(AGENT), null);

        assertThat(found).hasSize(1);
    }

    @Test
    void anAddressThatDiffersOnlyInCaseIsTheSameAuthor(@TempDir Path root) throws Exception {

        // Addresses are not case sensitive in practice and an agent definition is written by hand.
        final Path work = repository(root);
        commit(work, "one.txt", "Agent@Sokar.Invalid", "one");
        final String head = git(work, "rev-parse", "HEAD");

        final var found = GateCheckCommand.offenders(runner, work,
                List.of(hookLine(head, GateCheckCommand.ABSENT)), Set.of(AGENT), null);

        assertThat(found).hasSize(1);
    }

    @Test
    void aMachineWithNoAgentsInstalledFindsNothingToRefuse(@TempDir Path root) throws Exception {

        final Path work = repository(root);
        commit(work, "one.txt", AGENT, "one");
        final String head = git(work, "rev-parse", "HEAD");

        final var found = GateCheckCommand.offenders(runner, work,
                List.of(hookLine(head, GateCheckCommand.ABSENT)), Set.of(), null);

        assertThat(found).isEmpty();
    }

    @Test
    void anythingThatIsNotAHookLineIsIgnored(@TempDir Path root) throws Exception {

        // Somebody running this by hand, or a stray blank line, must not be read as a ref with an
        // empty sha - which git would answer for with an error the check would have to guess at.
        final Path work = repository(root);
        commit(work, "one.txt", AGENT, "one");

        final var found = GateCheckCommand.offenders(runner, work,
                List.of("", "some words", "refs/heads/main abc"), Set.of(AGENT), null);

        assertThat(found).isEmpty();
    }
}
