package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * Tests for restoring a mirror, against real repositories and real bundles.
 * <p>
 * The refusal is what matters and it cannot be faked: whether a mirror holds a push nobody has
 * reviewed is a question about refs in a real repository, and a fake runner would only prove that
 * the arguments match what was written down.
 */
class BackupRestoreTest {

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(60));

    private String git(Path directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(new Command(all, directory,
                Map.of("GIT_AUTHOR_NAME", "T", "GIT_AUTHOR_EMAIL", "t@x",
                        "GIT_COMMITTER_NAME", "T", "GIT_COMMITTER_EMAIL", "t@x",
                        "GIT_TERMINAL_PROMPT", "0"), null)).standardOutput().strip();
    }

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    /** The gate this machine's context would use - never the real one. */
    private org.fuin.sokar.gate.GitGate gateFor(Path dir, Path file) {
        final org.fuin.sokar.core.project.Project read = GateSupport.project(file);
        return new org.fuin.sokar.gate.GitGate(runner,
                context(dir).paths().xdg().data().resolve("mirrors")
                        .resolve(read.name() + ".git"),
                org.fuin.sokar.gate.GateMode.of(read.securityClass()), read.upstream());
    }

    /** Registers a project so the inventory knows it, and returns its file. */
    private Path project(Path dir) throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """);
        new ProjectRegistry(context(dir).paths().projectRegistry()).remember("demo", file);
        return file;
    }

    @Test
    void aMirrorHoldingAnUnreviewedPushIsNotOverwritten(@TempDir Path dir) throws Exception {

        // The refusal this exists for. An incoming ref is not on the upstream, not in a workspace
        // and not in the bundle, so restoring over it destroys the only copy there has ever been.
        final Path file = project(dir);
        final org.fuin.sokar.gate.GitGate gate = gateFor(dir, file);
        gate.initialize();
        final Path work = dir.resolve("work");
        git(dir, "init", "--quiet", "--initial-branch=main", work.toString());
        Files.writeString(work.resolve("a.txt"), "a\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "a");
        git(work, "push", "--quiet", gate.mirror().toString(),
                "main:refs/sokar/incoming/review-me");

        final Path bundle = dir.resolve("backup.bundle");
        Files.writeString(bundle, "not a bundle");

        final BackupRestore.Result result =
                BackupRestore.restore(context(dir), "demo", bundle, false, false);

        assertThat(result.outcome()).isEqualTo(BackupRestore.Outcome.HOLDS_WORK);
        assertThat(result.unreviewed()).containsExactly("review-me");
        assertThat(result.detail()).contains("only here");
    }

    @Test
    void anUnknownProjectIsRefusedRatherThanGuessed(@TempDir Path dir) throws Exception {

        final Path bundle = dir.resolve("backup.bundle");
        Files.writeString(bundle, "not a bundle");

        assertThat(BackupRestore.restore(context(dir), "nobody", bundle, false, false).outcome())
                .isEqualTo(BackupRestore.Outcome.NO_SUCH_PROJECT);
    }

    @Test
    void aBundleThatIsNotThereIsSaidSoBeforeAnythingIsTouched(@TempDir Path dir) throws Exception {

        project(dir);

        assertThat(BackupRestore.restore(context(dir), "demo", dir.resolve("gone.bundle"),
                false, false).outcome()).isEqualTo(BackupRestore.Outcome.NO_SUCH_BACKUP);
    }

    @Test
    void aRealBundleRestoresIntoAnEmptyMirror(@TempDir Path dir) throws Exception {

        final Path file = project(dir);
        final org.fuin.sokar.gate.GitGate gate = gateFor(dir, file);
        gate.initialize();
        final Path work = dir.resolve("work");
        git(dir, "init", "--quiet", "--initial-branch=main", work.toString());
        Files.writeString(work.resolve("a.txt"), "a\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "a");
        git(work, "push", "--quiet", gate.mirror().toString(), "main");

        final Path bundle = dir.resolve("backup.bundle");
        gate.backup(bundle);
        gate.deleteMirror();

        final BackupRestore.Result result =
                BackupRestore.restore(context(dir), "demo", bundle, false, false);

        assertThat(result.outcome()).isEqualTo(BackupRestore.Outcome.RESTORED);
        assertThat(gate.mirror().resolve("objects")).isDirectory();
    }

    @Test
    void forceProceedsAndStillNamesWhatItDestroyed(@TempDir Path dir) throws Exception {

        // Somebody who forced needs it in the record afterwards, not only in the warning they
        // clicked past.
        final Path file = project(dir);
        final org.fuin.sokar.gate.GitGate gate = gateFor(dir, file);
        gate.initialize();
        final Path work = dir.resolve("work");
        git(dir, "init", "--quiet", "--initial-branch=main", work.toString());
        Files.writeString(work.resolve("a.txt"), "a\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "a");
        git(work, "push", "--quiet", gate.mirror().toString(), "main");
        final Path bundle = dir.resolve("backup.bundle");
        gate.backup(bundle);
        git(work, "push", "--quiet", gate.mirror().toString(),
                "main:refs/sokar/incoming/after-the-backup");

        final BackupRestore.Result result =
                BackupRestore.restore(context(dir), "demo", bundle, false, true);

        assertThat(result.outcome()).isEqualTo(BackupRestore.Outcome.RESTORED);
        assertThat(result.unreviewed()).containsExactly("after-the-backup");
    }

    @Test
    void aPreviewNamesTheCostAndChangesNothing(@TempDir Path dir) throws Exception {

        final Path file = project(dir);
        final org.fuin.sokar.gate.GitGate gate = gateFor(dir, file);
        gate.initialize();
        final Path work = dir.resolve("work");
        git(dir, "init", "--quiet", "--initial-branch=main", work.toString());
        Files.writeString(work.resolve("a.txt"), "a\n");
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "a");
        git(work, "push", "--quiet", gate.mirror().toString(), "main");
        final Path bundle = dir.resolve("backup.bundle");
        gate.backup(bundle);

        final BackupRestore.Result result =
                BackupRestore.restore(context(dir), "demo", bundle, true, false);

        assertThat(result.outcome()).isEqualTo(BackupRestore.Outcome.PREVIEWED);
        assertThat(gate.mirror().resolve("objects")).isDirectory();
    }
}
