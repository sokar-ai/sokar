package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.HookInstaller;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Makes this account ready to run tasks: the OCI hooks, the daemon, and - at a terminal - the vault.
 * <p>
 * <strong>One command for what belongs to the account</strong>, because a package installed as root cannot
 * do it: podman reads hook descriptors per user, the daemon is a user service that starts in that user's
 * session, and a vault is made with a passphrase that only a person at a terminal may type. A wizard opens
 * a terminal over ssh ({@code ssh -t}) and runs this; nobody has to know the steps.
 * <p>
 * <strong>Each step only when it is not done</strong>, so a run abandoned halfway and started again does not
 * fail on what already happened. Without a terminal the vault is left for later and said so, never asked
 * for in a way that fails: a script that runs this to register the hooks keeps working.
 * <p>
 * Kept out of the package's install scripts on purpose. Writing into an operator's podman
 * configuration is host integration, not file installation, and it should happen when the operator
 * asks for it rather than as a side effect of {@code apt install}.
 */
@Command(name = "setup",
        mixinStandardHelpOptions = true,
        description = "Makes this account ready to run tasks: registers the hooks, starts the daemon and,"
                + " at a terminal, creates the vault. Each only when it is not done yet.")
public class SetupCommand implements Callable<Integer>, SokarFactory.ContextAware {

    /** The user service the package installs. */
    static final String DAEMON = "sokard";

    @Option(names = "--uninstall", description = "Removes the hook descriptors again.")
    private boolean uninstall;

    @Option(names = "--hooks-only", description = "Registers the hooks and nothing else, as setup did before it"
            + " started the daemon and made the vault.")
    private boolean hooksOnly;

    /** Whether a person is at a terminal to type a passphrase; replaced in tests. */
    private java.util.function.BooleanSupplier terminal = () -> System.console() != null;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final HookInstaller installer = context.hooks();

        if (uninstall) {
            // One line per file, the same shape as installing. The count-and-one-directory form
            // this replaced named the hooks directory for all of them, and the drop-in is not in
            // it - so the one file somebody is most likely to go looking for by hand was the one
            // reported in the wrong place.
            final java.util.List<java.nio.file.Path> removed = installer.uninstall();
            removed.forEach(file -> out.println("removed   " + file));
            if (removed.isEmpty()) {
                out.println("nothing   there was nothing of ours to remove");
            }
            out.flush();
            return 0;
        }

        if (!installer.binariesPresent()) {
            err.println("sokar: the hook binaries are not in " + context.paths().binaryDirectory());
            err.flush();
            return 69;
        }

        final java.util.List<Path> installed = installer.install();
        installed.forEach(file -> out.println("installed " + file));
        if (hooksOnly) {
            out.println();
            out.println("The hooks only fire for containers carrying Sokar's own annotation,");
            out.println("so other containers on this machine are unaffected.");
            out.flush();
            return 0;
        }
        out.println("hooks     registered; they only fire for containers carrying Sokar's own annotation");
        daemon(out);
        final boolean vault = vault(out, err);
        out.println();
        final java.util.List<String> missing = DoctorCommand.probesFor(context).stream()
                .filter(probe -> probe.state() == Probe.State.MISSING).map(Probe::name).toList();
        if (missing.isEmpty() && vault) {
            out.println("Ready: this account can run tasks. 'sokar doctor' says more.");
        } else {
            out.println("Not ready yet" + (missing.isEmpty() ? "" : ": " + String.join(", ", missing))
                    + (vault ? "" : (missing.isEmpty() ? ": " : "; ") + "no vault")
                    + ". 'sokar doctor' says what to do about each.");
        }
        out.flush();
        return 0;
    }

    /**
     * Starts the daemon for good, unless it runs already.
     * <p>
     * Not a failure when it cannot: an account whose user manager is not reachable from here - no lingering, no
     * session - can still run a task from the command line, and is told what to run instead.
     */
    private void daemon(final PrintWriter out) {
        final org.fuin.sokar.core.process.CommandRunner runner = context.runner();
        if (runner.run(org.fuin.sokar.core.process.Command.of("systemctl", "--user", "is-active", "--quiet",
                DAEMON)).exitCode() == 0) {
            out.println("daemon    already running");
            return;
        }
        final org.fuin.sokar.core.process.CommandResult started = runner.run(
                org.fuin.sokar.core.process.Command.of("systemctl", "--user", "enable", "--now", DAEMON));
        if (started.exitCode() == 0) {
            out.println("daemon    started, and starts with this account from now on");
            return;
        }
        final String why = started.standardError().strip().lines().findFirst().orElse("exit " + started.exitCode());
        out.println("daemon    not started: " + why + " - start it in this account's own session with"
                + " 'systemctl --user enable --now " + DAEMON + "'");
    }

    /**
     * Makes the vault when there is none and a person is at a terminal to choose its passphrase.
     *
     * @return Whether there is a vault afterwards.
     */
    private boolean vault(final PrintWriter out, final PrintWriter err) {
        if (context.vault().exists()) {
            out.println("vault     already there");
            return true;
        }
        if (!terminal.getAsBoolean()) {
            // The one step nothing may do for a person. Said rather than attempted: without a terminal the
            // prompt fails as "no passphrase available", which reads as a fault of Sokar's and is a missing -t.
            out.println("vault     none yet; it needs a passphrase typed at a terminal - run 'sokar setup' or"
                    + " 'sokar vault init' in one (over ssh: 'ssh -t')");
            return false;
        }
        out.println("vault     none yet; choose the passphrase that opens it");
        out.flush();
        final VaultInitCommand init = new VaultInitCommand();
        init.setContext(context);
        final picocli.CommandLine command = new picocli.CommandLine(init);
        command.setOut(out);
        command.setErr(err);
        return command.execute() == 0;
    }

    /**
     * Replaces how a terminal is recognised, for a test.
     *
     * @param present Whether a person is at one.
     */
    void terminal(final java.util.function.BooleanSupplier present) {
        this.terminal = present;
    }
}
