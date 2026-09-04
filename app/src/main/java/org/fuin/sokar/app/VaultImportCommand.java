package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.sokar.agent.api.Credential;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultException;
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
    private String agentName;

    @Option(names = "--config-dir", paramLabel = "<dir>",
            description = "Where the agent keeps its credentials. Default: what the agent declares.")
    private String configDirectory;

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
        return path.startsWith("~")
                ? Path.of(System.getProperty("user.home"), path.substring(1))
                : Path.of(path);
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try (var agents = context.agents()) {

            final Optional<InstalledAgent> found = agentName != null ? agents.find(agentName)
                    : agents.names().size() == 1 ? agents.find(agents.names().getFirst())
                            : Optional.empty();
            if (found.isEmpty()) {
                err.println("sokar: no such agent; installed: "
                        + String.join(", ", agents.names()));
                err.flush();
                return 69;
            }
            final InstalledAgent agent = found.get();

            final String declared = configDirectory != null
                    ? configDirectory : agent.definition().configDirectory();
            if (declared == null) {
                err.println("sokar: '" + agent.name() + "' does not say where it keeps its"
                        + " credentials; pass --config-dir");
                err.flush();
                return 69;
            }

            final Path directory = expand(declared);
            final Optional<Credential> credential = agent.extractCredential(directory);
            if (credential.isEmpty()) {
                err.println("sokar: nothing to import from " + directory
                        + " - log in with the agent on this host first");
                err.flush();
                return 69;
            }

            final Credential value = credential.get();
            context.vault().update(context.requirePassphrase(), entries -> {
                entries.put(agent.name(), new VaultEntry(value.secret(), value.type()));
                return entries;
            });
            // The value is never echoed: what is useful is that it arrived and which kind it is.
            out.println("imported  " + agent.name() + " (" + value.type() + ", "
                    + value.secret().length() + " characters) from " + directory);
            out.flush();

            final var route = agent.definition().route();
            final String reason = route == null ? null : route.unbrokerableReason(value.type());
            if (reason != null) {
                err.println("sokar: stored, but a task will refuse it. " + reason + ".");
                err.println("sokar: store a usable one with: sokar vault put " + agent.name()
                        + " --type <kind>");
                err.flush();
            }
            return 0;

        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
