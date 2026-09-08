package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for installing the pre-push hook.
 * <p>
 * Against real repositories, because the two things worth getting right are both git's answers
 * rather than ours: where the hooks directory is for this checkout, and whether git would even
 * look there.
 */
class GateProtectCommandTest {

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(60));

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private String git(Path directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(new Command(all, directory, Map.of(), null))
                .standardOutput().strip();
    }

    private int run(Path dir, String... arguments) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(runner,
                new SokarPaths(xdg, dir.resolve("bin")), command -> 0);
        final CommandLine cmd = new CommandLine(new SokarCli(), new SokarFactory(context));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(arguments);
    }

    private Path repository(Path root) {
        final Path work = root.resolve("work");
        git(root, "init", "--quiet", "--initial-branch=main", work.toString());
        return work;
    }

    @Test
    void installsAHookGitWillActuallyRun(@TempDir Path root) throws Exception {

        final Path work = repository(root);

        assertThat(run(root, "gate", "protect", "--repo", work.toString())).isZero();

        final Path hook = work.resolve(".git").resolve("hooks").resolve("pre-push");
        assertThat(hook).exists().isExecutable();
        assertThat(Files.readString(hook)).contains("sokar gate check");
        assertThat(out.toString()).contains("installed");
    }

    @Test
    void saysSoWhenGitWillNotLookAtTheHookItJustWrote(@TempDir Path root) throws Exception {

        // Measured: with core.hooksPath set elsewhere the repository's own hooks directory is
        // never read, and a push goes through with no output at all. This is the dangerous one -
        // it is often a team-wide setting somebody configured long ago for shared hooks, and a
        // command that wrote the file and reported success would leave somebody believing they
        // were protected when nothing would ever fire.
        final Path work = repository(root);
        final Path elsewhere = root.resolve("shared-hooks");
        Files.createDirectories(elsewhere);
        git(work, "config", "core.hooksPath", elsewhere.toString());

        assertThat(run(root, "gate", "protect", "--repo", work.toString())).isZero();

        assertThat(work.resolve(".git").resolve("hooks").resolve("pre-push")).exists();
        assertThat(err.toString()).contains("core.hooksPath").contains("not protected");
    }

    @Test
    void refusesToOverwriteSomebodyElsesHook(@TempDir Path root) throws Exception {

        final Path work = repository(root);
        final Path hook = work.resolve(".git").resolve("hooks").resolve("pre-push");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\necho theirs\n");

        assertThat(run(root, "gate", "protect", "--repo", work.toString())).isEqualTo(2);

        assertThat(Files.readString(hook)).isEqualTo("#!/bin/sh\necho theirs\n");
        assertThat(err.toString()).contains("already exists");
    }

    @Test
    void removesOnlyTheHookItWrote(@TempDir Path root) throws Exception {

        final Path work = repository(root);
        final Path hook = work.resolve(".git").resolve("hooks").resolve("pre-push");
        Files.createDirectories(hook.getParent());
        Files.writeString(hook, "#!/bin/sh\necho theirs\n");

        assertThat(run(root, "gate", "protect", "--repo", work.toString(), "--remove")).isZero();

        assertThat(hook).as("a command that tidied away a file it never wrote would be worse than"
                + " the problem it solves").exists();
        assertThat(out.toString()).contains("nothing");
    }

    @Test
    void takesItsOwnHookAwayAgain(@TempDir Path root) throws Exception {

        final Path work = repository(root);
        run(root, "gate", "protect", "--repo", work.toString());

        assertThat(run(root, "gate", "protect", "--repo", work.toString(), "--remove")).isZero();

        assertThat(work.resolve(".git").resolve("hooks").resolve("pre-push")).doesNotExist();
        assertThat(out.toString()).contains("removed");
    }

    @Test
    void installingTwiceIsNotAnError(@TempDir Path root) throws Exception {

        // Somebody re-running it after an upgrade must get the newer hook, not a refusal that
        // reads as though somebody else's file were in the way.
        final Path work = repository(root);
        run(root, "gate", "protect", "--repo", work.toString());

        assertThat(run(root, "gate", "protect", "--repo", work.toString())).isZero();

        assertThat(err.toString()).doesNotContain("already exists");
    }

    @Test
    void refusesADirectoryThatIsNotARepository(@TempDir Path root) throws Exception {

        final Path plain = root.resolve("plain");
        Files.createDirectories(plain);

        assertThat(run(root, "gate", "protect", "--repo", plain.toString())).isEqualTo(2);

        assertThat(err.toString()).contains("not a git repository");
    }

    @Test
    void findsTheHooksDirectoryOfARepositoryWhoseGitDirIsElsewhere(@TempDir Path root)
            throws Exception {

        // A worktree, a submodule and a --separate-git-dir checkout all have their hooks somewhere
        // other than .git/hooks. Asking git rather than assuming the layout is what makes it work
        // for all three - and a hook written to a .git file rather than a directory would be a
        // silent nothing.
        final Path work = repository(root);
        Files.writeString(work.resolve("one.txt"), "one\n");
        git(work, "add", ".");
        git(work, "-c", "user.name=T", "-c", "user.email=t@x", "commit", "--quiet", "-m", "one");
        final Path tree = root.resolve("tree");
        git(work, "worktree", "add", "--quiet", "-b", "side", tree.toString());

        assertThat(run(root, "gate", "protect", "--repo", tree.toString())).isZero();

        assertThat(work.resolve(".git").resolve("hooks").resolve("pre-push"))
                .as("a worktree shares the hooks of the repository it belongs to").exists();
    }
}
