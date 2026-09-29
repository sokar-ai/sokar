package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.ContainerSummary;
import org.fuin.sokar.vault.KernelKeyring;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Drops the cached passphrase, so the next command asks for it again.
 * <p>
 * A verb of its own rather than a flag on {@code unlock}, because an operator who wants the vault
 * shut does not go looking for the opposite command - and this is a security control, which is the
 * worst kind of thing to hide behind the option of its inverse.
 */
@Command(name = "lock",
        mixinStandardHelpOptions = true,
        description = "Drops the cached passphrase. The next command asks for it again.")
public class VaultLockCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Forgets the cached passphrase and says what that did and did not do.
     * <p>
     * Shared with {@code unlock --forget}, which is the same operation under its older name.
     *
     * @param context Where the keyring name and the running tasks come from.
     * @param out Where to report.
     * @return Exit code. Zero whether or not anything was cached - the wanted state is "not
     *         cached", and it holds either way - and non-zero when the keyring could not say,
     *         because zero would let a script believe it had locked something.
     */
    static int lock(SokarContext context, PrintWriter out) {

        if (!KernelKeyring.available()) {
            // Nothing can be cached without the keyring, so nothing is. Reporting "locked" would
            // claim a change that did not happen.
            out.println("nothing is cached: libkeyutils is not installed, so no passphrase is kept");
            out.flush();
            return 0;
        }

        // Both ways in, not only the passphrase: a device's share held here opens the vault as well, and
        // forgetting the passphrase must not silently keep a device's way in. Measured: with a share held,
        // 'lock' said "locked" while every command still opened the vault.
        final KernelKeyring.Forgotten passphrase =
                new KernelKeyring(context.paths().vaultKeyringKey()).forget();
        final KernelKeyring.Forgotten share = VaultShare.forget(context.paths());
        final KernelKeyring.Forgotten forgotten = passphrase == KernelKeyring.Forgotten.UNKNOWN
                || share == KernelKeyring.Forgotten.UNKNOWN ? KernelKeyring.Forgotten.UNKNOWN
                : passphrase == KernelKeyring.Forgotten.CLEARED || share == KernelKeyring.Forgotten.CLEARED
                        ? KernelKeyring.Forgotten.CLEARED : KernelKeyring.Forgotten.NOTHING_CACHED;
        if (forgotten == KernelKeyring.Forgotten.UNKNOWN) {
            // Never reported as "nothing was cached": that is a claim about a secret nobody
            // checked, and the passphrase may still be sitting in the keyring.
            out.println("could not be established whether a passphrase is cached; the keyring did"
                    + " not answer. Check 'keyctl show @u' and try again, and treat the"
                    + " passphrase as still cached until it does.");
            out.flush();
            return 70;
        }
        out.println(forgotten == KernelKeyring.Forgotten.CLEARED
                ? "locked    the next command asks for the passphrase again"
                : "nothing was cached");

        // Said only when it is true, and it changes what locking means: a running task's proxy
        // read its credential when it started and holds it in its own memory, where this cannot
        // reach. Stopping the task is what ends that.
        final long running = context.podman().sokarTasks().stream()
                .filter(ContainerSummary::running).count();
        if (running > 0) {
            out.println("running   " + running + " task" + (running == 1 ? "" : "s")
                    + " still hold the credential their proxy already read; stop them to end that");
        }
        out.flush();
        return 0;
    }

    @Override
    public Integer call() {
        return lock(context, spec.commandLine().getOut());
    }
}
