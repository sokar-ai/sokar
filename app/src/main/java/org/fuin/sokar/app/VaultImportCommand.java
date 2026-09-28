package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.sokar.agent.api.Credential;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Copies a credential an agent already holds on this host into the vault.
 * <p>
 * An agent that has been logged in keeps its credential in a file of its own. Retyping it into the
 * vault is work that invites a typo, and the commonest typo is storing the guide's placeholder.
 */
@Command(name = "import",
        mixinStandardHelpOptions = true,
        description = "Copies a credential the agent already holds on this host into the vault.")
public class VaultImportCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<agent>",
            description = "Agent to import from. Default: the only one installed.")
    private @Nullable String agentName;

    @Option(names = "--config-dir", paramLabel = "<dir>",
            description = "Where the agent keeps its credentials. Default: what the agent declares.")
    private @Nullable String configDirectory;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Expands a leading tilde, which a declared path is written with.
     *
     * @param path Path as declared.
     * @return Absolute path.
     */
    static Path expand(String path) {
        if (!path.startsWith("~")) {
            return Path.of(path);
        }
        // '~' alone, '~/' and '~/x' all have to work: the old form leaned on Path.of ignoring a
        // leading slash in its second argument, and taking substring(2) of "~" would throw.
        final String rest = path.substring(1).replaceFirst("^/+", "");
        final Path home = org.fuin.sokar.core.config.XdgPaths.home(System::getenv);
        return rest.isEmpty() ? home : home.resolve(rest);
    }

    /**
     * Caches the passphrase that was just typed, so the next command does not ask again.
     * <p>
     * Importing is normally the first thing an operator does, and being asked twice in a row for
     * the same secret reads as something having gone wrong.
     *
     * @param passphrase The passphrase.
     * @param out Where to report.
     */
    private void cache(char[] passphrase, PrintWriter out) {
        if (!org.fuin.sokar.vault.KernelKeyring.available()) {
            return;
        }
        try {
            new org.fuin.sokar.vault.KernelKeyring(context.paths().vaultKeyringKey()).store(passphrase);
            out.println("unlocked  cached in this account's keyring, where the daemon finds it");
        } catch (RuntimeException ex) {
            // Not being able to cache is not a reason for the import to have failed.
            return;
        }
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // How the vault is opened is the only thing that differs from the daemon's import: here
        // there is a terminal to ask at, so a vault that nothing already opens is a prompt rather
        // than a refusal. What already opens it - a cached passphrase, or a device's share - is
        // used first, so somebody at the machine is not asked for something they do not need.
        final char[][] typed = new char[1][];
        final CredentialImport.Result result = CredentialImport.run(context, agentName,
                configDirectory, () -> context.opener().or(() -> {
                    typed[0] = context.requirePassphrase();
                    return Optional.of(org.fuin.sokar.vault.VaultFile.Opener.passphrase(typed[0]));
                }));

        if (result.outcome() != CredentialImport.Outcome.IMPORTED) {
            err.println("sokar: " + result.detail());
            err.flush();
            return result.outcome() == CredentialImport.Outcome.FAILED ? 70 : 69;
        }

        if (typed[0] != null) {
            cache(typed[0], out);
        }
        // The value is never echoed: what is useful is that it arrived and which kind it is.
        out.println("imported  " + result.name() + " (" + result.type() + ", "
                + result.length() + " characters) from " + result.source());
        out.flush();
        if (!result.detail().isEmpty()) {
            err.println("sokar: " + result.detail());
            err.flush();
        }
        return 0;
    }
}
