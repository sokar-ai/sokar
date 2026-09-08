package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.process.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Installs a {@code pre-push} hook that says when a push carries unapproved agent work.
 * <p>
 * <strong>A deliberate act, in somebody else's repository.</strong> The operator's checkout is not
 * Sokar's to change, so this is a command they run rather than something a task start does. It
 * writes one file, says what it wrote, and {@code --remove} takes it away again.
 * <p>
 * <strong>What it can and cannot promise.</strong> Measured: the hook fires on a first push, an
 * ordinary push and a force push, and can see exactly which commits are leaving. Four things go
 * round it - {@code --no-verify}, a {@code core.hooksPath} pointing elsewhere, a fresh clone
 * (hooks are per clone and not committed), and a client built on a git library rather than the git
 * command. So this is a loud accident-catcher and not a control, and anything that presented it as
 * prevention would be the lie this requirement's own notes warn against.
 */
@picocli.CommandLine.Command(name = "protect",
        mixinStandardHelpOptions = true,
        description = "Installs a pre-push hook that catches unapproved agent work.")
public class GateProtectCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--repo", paramLabel = "<dir>",
            description = "The checkout to protect. Default: this directory.")
    private Path repository = Path.of(".");

    @Option(names = "--remove", description = "Takes the hook away again.")
    private boolean remove;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Returns the hook's text.
     * <p>
     * Two lines of shell and nothing more: everything that could be wrong is decided by
     * {@code sokar gate check}, which is testable, and a hook nobody can read is a hook nobody
     * trusts. The marker line is how {@code --remove} knows it wrote this one rather than
     * somebody else's.
     *
     * @return The script.
     */
    static String hook() {
        return """
                #!/bin/sh
                # sokar-gate-protect
                # Refuses a push carrying commits an agent wrote that nobody approved at the gate.
                # Remove with 'sokar gate protect --remove', or bypass once with 'git push --no-verify'.
                exec sokar gate check --quiet "$@"
                """;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // --git-common-dir, not --git-dir. Measured in a worktree: git runs pre-push out of the
        // common directory, and the worktree's own git dir has no hooks directory at all - so the
        // obvious query would create one, report success, and never fire.
        final var top = context.runner().run(Command.of(
                List.of("git", "-C", repository.toString(), "rev-parse", "--git-common-dir")));
        if (!top.successful()) {
            err.println("sokar: " + repository.toAbsolutePath() + " is not a git repository");
            err.flush();
            return 2;
        }
        final Path gitDir = repository.resolve(top.standardOutput().strip());
        final Path hookFile = gitDir.resolve("hooks").resolve("pre-push");

        if (remove) {
            try {
                if (Files.isRegularFile(hookFile)
                        && Files.readString(hookFile, StandardCharsets.UTF_8)
                                .contains("sokar-gate-protect")) {
                    Files.delete(hookFile);
                    out.println("removed   " + hookFile);
                } else {
                    // Never deletes somebody else's hook. A command that tidied away a file it did
                    // not write would be worse than the problem it solves.
                    out.println("nothing   no hook of ours at " + hookFile);
                }
                out.flush();
                return 0;
            } catch (IOException ex) {
                err.println("sokar: cannot remove " + hookFile + ": " + ex.getMessage());
                err.flush();
                return 70;
            }
        }

        try {
            Files.createDirectories(hookFile.getParent());
            if (Files.isRegularFile(hookFile) && !Files.readString(hookFile, StandardCharsets.UTF_8)
                    .contains("sokar-gate-protect")) {
                err.println("sokar: " + hookFile + " already exists and is not ours - it was left"
                        + " alone. Move it aside, or add 'sokar gate check --quiet' to it.");
                err.flush();
                return 2;
            }
            Files.writeString(hookFile, hook(), StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(hookFile, PosixFilePermissions.fromString("rwxr-xr-x"));
            out.println("installed " + hookFile);
        } catch (IOException ex) {
            err.println("sokar: cannot write " + hookFile + ": " + ex.getMessage());
            err.flush();
            return 70;
        }

        // Measured: with core.hooksPath set elsewhere, the repository's own hook is never found
        // and a push goes through with no output at all. Reporting success here without saying so
        // would leave somebody believing they are protected - which is worse than not installing.
        final var hooksPath = context.runner().run(Command.of(
                List.of("git", "-C", repository.toString(), "config", "core.hooksPath")));
        if (hooksPath.successful() && !hooksPath.standardOutput().isBlank()) {
            err.println();
            err.println("sokar: WARNING - core.hooksPath is set to "
                    + hooksPath.standardOutput().strip() + ", so git will NOT run the hook just"
                    + " installed. Until that setting is changed or the hook is placed there"
                    + " instead, this repository is not protected.");
            err.flush();
            out.flush();
            return 0;
        }

        out.println("catches   a push carrying commits an agent wrote that nobody approved");
        out.println("bypass    'git push --no-verify', which is how somebody who means it proceeds");
        out.println("note      hooks are per clone: a fresh clone of this repository has none");
        out.flush();
        return 0;
    }
}
