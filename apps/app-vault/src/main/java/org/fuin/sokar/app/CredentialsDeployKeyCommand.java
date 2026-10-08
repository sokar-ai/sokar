package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Makes this machine a deploy key for a repository's upstream, and prints its public half for a person to add
 * to the forge, once.
 * <p>
 * <strong>One deploy key per machine per repository</strong>: the secret half goes into this machine's vault
 * and never leaves it, and it is declared for the repository's upstream so the host fetches, and forwards
 * approved work, with it. <strong>Read-only for the project's own repository</strong>, write access for a work
 * repository, unless said otherwise. A machine that already has a key it wants to use declares that one instead,
 * with {@code sokar credentials declare}.
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

    @Option(names = "--read-only", description = "To be registered read-only. The default for the project's own"
            + " repository, which a machine reads and never writes.")
    private boolean readOnly;

    @Option(names = "--write", description = "To be registered with write access. The default for a work"
            + " repository, where approved work is pushed.")
    private boolean write;

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
        if (readOnly && write) {
            err.println("sokar: --read-only and --write say opposite things. Pick one.");
            err.flush();
            return 64;
        }
        final Project project = GateSupport.byName(context, projectName);
        // The keyring's if the vault is unlocked, else the passphrase asked here: a terminal can ask, the daemon cannot.
        final org.fuin.sokar.vault.VaultFile.Opener way = context.opener()
                .orElseGet(() -> org.fuin.sokar.vault.VaultFile.Opener.passphrase(context.requirePassphrase()));
        final DeployKeys.Key key;
        try {
            key = DeployKeys.make(context, way, project, repository,
                    readOnly ? Boolean.TRUE : write ? Boolean.FALSE : null, fresh);
        } catch (DeployKeys.Refused ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 64;
        }
        out.println((key.made() ? "made      " : "deploy key ") + key.entry() + " for " + key.upstream()
                + ", kept in this machine's vault");
        out.println("add it to the forge as a deploy key " + (key.writeAccess() ? "with write access"
                : "read-only") + ", titled '" + key.title() + "', once:");
        out.println();
        out.println("    " + key.publicKey());
        out.println();
        out.flush();
        return 0;
    }
}
