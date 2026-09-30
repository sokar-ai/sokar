package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.credential.Credential;
import org.fuin.sokar.core.credential.CredentialRegistry;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.Repository;
import org.fuin.sokar.vault.SigningKey;
import org.fuin.sokar.vault.VaultEntry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Makes this machine a deploy key for a repository's upstream, and prints its public half for a person to add
 * to the forge, once.
 * <p>
 * <strong>One deploy key per machine</strong> (decided for the project on 2026-09-29): the secret half goes
 * into this machine's vault and never leaves it, and it is declared for the repository's upstream so the gate
 * forwards approved work with it. Never a forge application whose private key would sit on every machine.
 * A machine that already has a key it wants to use declares that one instead, with
 * {@code sokar credentials declare}.
 */
@Command(name = "deploy-key",
        mixinStandardHelpOptions = true,
        description = "Makes this machine a deploy key for a repository's upstream and prints its public half.")
public class CredentialsDeployKeyCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project name.")
    private String projectName;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the project's own.")
    private @org.jspecify.annotations.Nullable String repository;

    @Option(names = "--new", description = "Make a new key although this machine has one for it; the old one is"
            + " forgotten here and should be removed at the forge.")
    private boolean fresh;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Project project = GateSupport.byName(context, projectName);
        final Repository chosen = GateSupport.repository(project, repository);
        if (chosen.upstream() == null) {
            err.println("sokar: '" + chosen.name() + "' has no upstream, so there is no forge to give a key to");
            err.flush();
            return 64;
        }
        final String entry = "deploy-" + projectName + "-" + chosen.name();
        final String machine = "sokar@" + hostName();
        // What it would be declared as, before any key is made: an upstream nothing authenticates to - a path on
        // this machine - is refused with nothing left behind in the vault.
        final Credential declared;
        try {
            declared = CredentialDeclarations.named(new Credential(entry, Credential.Kind.SSH_KEY,
                    CredentialRegistry.normalise(chosen.upstream()), null, Credential.ANY, Credential.Source.VAULT));
            CredentialDeclarations.checkPossible(declared);
        } catch (org.fuin.sokar.core.credential.CredentialException ex) {
            err.println("sokar: no deploy key for " + chosen.upstream() + ": " + ex.getMessage());
            err.flush();
            return 64;
        }
        final char[] passphrase = context.requirePassphrase();
        final VaultEntry existing = context.vault().exists() ? context.vault().read(passphrase).get(entry) : null;
        final org.fuin.sokar.vault.AgentKey key;
        if (existing != null && !fresh) {
            key = org.fuin.sokar.vault.StoredKey.of(existing.value(), machine);
        } else {
            final SigningKey made = SigningKey.generate(machine);
            key = made;
            final VaultEntry kept = new VaultEntry(made.seedBase64(), "ssh-key",
                    Map.of("upstream", chosen.upstream(), "made_on", machine));
            context.vault().update(passphrase, all -> {
                all.put(entry, kept);
                return all;
            });
        }
        try {
            new CredentialDeclarations(context).declare(declared);
        } catch (org.fuin.sokar.core.credential.CredentialException | java.io.IOException ex) {
            err.println("sokar: the key is in the vault as '" + entry + "', and could not be declared for "
                    + chosen.upstream() + ": " + ex.getMessage());
            err.flush();
            return 70;
        }
        out.println((existing != null && !fresh ? "deploy key " : "made      ") + entry + " for " + chosen.upstream()
                + ", kept in this machine's vault");
        out.println("add it to the forge as a deploy key with write access, once:");
        out.println();
        out.println("    " + key.authorizedKeysLine());
        out.println();
        out.flush();
        return 0;
    }

    private static String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (java.net.UnknownHostException ex) {
            return "localhost";
        }
    }
}
