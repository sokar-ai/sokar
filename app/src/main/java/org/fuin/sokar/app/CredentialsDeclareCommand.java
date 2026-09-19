package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.credential.Credential;
import org.fuin.sokar.core.credential.CredentialRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Records what a destination wants, without ever holding the value.
 * <p>
 * The value is typed separately - {@code vault put} for one this machine keeps, and nothing at all
 * for a key or a token that is already somewhere. That split is deliberate: it is what lets an
 * interface write this record over a socket no secret may cross.
 */
@Command(name = "declare",
        mixinStandardHelpOptions = true,
        description = "Records what a destination wants and where its value lives.")
public class CredentialsDeclareCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<match>",
            description = "The destinations this covers: a URL or its start, such as"
                    + " 'https://gitlab.example/acme/'. The longest match wins.")
    private String match;

    @Option(names = "--kind", paramLabel = "<kind>", required = true,
            description = "ssh-key, token, basic or oauth. Said rather than guessed: https may be"
                    + " either a token or a username and password.")
    private String kind;

    @Option(names = "--vault", paramLabel = "<entry>",
            description = "Keep the value in this machine's vault, under this name.")
    private String vaultEntry;

    @Option(names = "--file", paramLabel = "<path>",
            description = "Use a file that is already here, such as ~/.ssh/id_ed25519. Nothing is"
                    + " copied, and this machine does not protect it.")
    private String file;

    @Option(names = "--env", paramLabel = "<variable>",
            description = "Use a value already in the environment. Not protected here either.")
    private String variable;

    @Option(names = "--agent",
            description = "Use the ssh-agent this account already runs. Nothing is read or kept.")
    private boolean agent;

    @Option(names = "--user", paramLabel = "<name>",
            description = "Username, for 'basic' and for a token that needs one.")
    private String user;

    @Option(names = "--purpose", paramLabel = "<what>",
            description = "What it may be used for. Default: ${DEFAULT-VALUE}")
    private String purpose = Credential.ANY;

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

        final int said = (vaultEntry == null ? 0 : 1) + (file == null ? 0 : 1)
                + (variable == null ? 0 : 1) + (agent ? 1 : 0);
        if (said != 1) {
            err.println("sokar: say exactly one of --vault, --file, --env or --agent - where the"
                    + " value lives is the one thing this record is for.");
            err.flush();
            return 64;
        }
        final Credential.Kind chosen;
        try {
            chosen = Credential.Kind.valueOf(
                    kind.strip().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (final IllegalArgumentException ex) {
            err.println("sokar: '" + kind + "' is not a kind: ssh-key, token, basic or oauth");
            err.flush();
            return 64;
        }
        if (agent && chosen != Credential.Kind.SSH_KEY) {
            err.println("sokar: an ssh-agent answers with a key, so --agent goes with"
                    + " --kind ssh-key");
            err.flush();
            return 64;
        }
        final Credential.Source source = agent ? Credential.Source.AGENT
                : file != null ? Credential.Source.FILE
                : variable != null ? Credential.Source.ENVIRONMENT : Credential.Source.VAULT;
        // Blank rather than refused: a vault record with no name means "you name it", the same as
        // over the socket, and it is named from the destination below.
        final String id = agent ? "" : file != null ? file
                : variable != null ? variable : vaultEntry == null ? "" : vaultEntry;

        final Credential credential;
        try {
            credential = CredentialDeclarations.named(new Credential(id, chosen,
                    CredentialRegistry.normalise(match), user, purpose, source));
            new CredentialDeclarations(context).declare(credential);
        } catch (final org.fuin.sokar.core.credential.CredentialException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 64;
        } catch (final java.io.IOException ex) {
            err.println("sokar: cannot write the record: " + ex.getMessage());
            err.flush();
            return 70;
        }
        out.println("declared   " + credential.match() + "  " + chosen.name()
                + "  from " + source.name().toLowerCase(Locale.ROOT)
                + (id.isEmpty() ? "" : " '" + id + "'"));
        if (!credential.protectedHere()) {
            out.println("           this machine does not protect it - it is as safe as what"
                    + " holds it");
        }
        // Only when there is nothing to use yet. Telling somebody to store a value they already
        // stored reads as though the declaration had not taken.
        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context).check(match, purpose);
        final String store = CredentialDeclarations.storeCommandFor(credential);
        if (!store.isEmpty() && check.outcome() != CredentialDeclarations.Outcome.READY) {
            out.println("           store the value with: " + store);
        }
        out.flush();
        return 0;
    }
}
