package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Logs in with an agent and stores what it produces.
 * <p>
 * For the case the getting-started guide could not answer: a machine where the agent has never
 * been installed by hand, so there is no binary to run a login with and
 * {@code sokar vault put <provider> --type oauth} asks for a value that has no source.
 */
@Command(name = "login",
        mixinStandardHelpOptions = true,
        description = "Runs an agent's own login and stores the credential it produces.")
public class VaultLoginCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<agent>",
            description = "Agent to log in with. Default: the only one installed.")
    private @Nullable String agentName;

    @Option(names = "--dry-run", description = "Says what it would run and runs nothing.")
    private boolean dryRun;

    @Option(names = "--force",
            description = "Logs in again even though this machine already has a credential.")
    private boolean force;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    /** Runs a login: the agent's own, as {@link AgentLogin#login} does. */
    @FunctionalInterface
    interface Login {
        AgentLogin.Result run(SokarContext context, @Nullable String agent, boolean dryRun, boolean force,
                PrintWriter out);
    }

    /** Copies what an agent signed in with on this machine, as {@code sokar vault import} does. */
    @FunctionalInterface
    interface Importer {
        int run(@Nullable String agent);
    }

    /** Asks a person, or answers {@code null} when nobody is at a terminal to answer. */
    @FunctionalInterface
    interface Asker {
        @Nullable String ask(String question);
    }

    Login login = AgentLogin::login;

    Importer importer = agent -> agent == null ? spec.root().commandLine().execute("vault", "import")
            : spec.root().commandLine().execute("vault", "import", agent);

    Asker asker = question -> {
        final java.io.Console console = System.console();
        // A console object alone is no terminal: since Java 22 one exists for a pipe too.
        return console == null || !console.isTerminal() ? null : console.readLine("%s ", question);
    };

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        AgentLogin.Result result = login.run(context, agentName, dryRun, force, out);
        out.flush();

        if (result.outcome() == AgentLogin.Outcome.ALREADY_SIGNED_IN && !dryRun) {
            // What the refusal would tell a person to type, offered instead where somebody can answer.
            final String answer = asker.ask("'" + (agentName == null ? "the agent" : agentName) + "' is already signed"
                    + " in on this machine. Import what it has, which logs in nowhere; log in again, which may"
                    + " invalidate the credential here; or cancel? [I/a/c]");
            if (answer != null) {
                switch (answer.strip().toLowerCase(java.util.Locale.ROOT)) {
                    case "", "i", "import" -> {
                        return importer.run(agentName);
                    }
                    case "a", "again" -> {
                        result = login.run(context, agentName, false, true, out);
                        out.flush();
                    }
                    default -> {
                        out.println("Cancelled - nothing was done.");
                        out.flush();
                        return 0;
                    }
                }
            }
        }

        if (result.outcome() == AgentLogin.Outcome.STORED) {
            // The value is never echoed: what is useful is that it arrived and which kind it is.
            out.println("Signed in - the credential is stored as '" + result.name() + "' (" + result.type() + ", "
                    + result.length() + " characters). Nothing more to do here.");
            out.flush();
            return 0;
        }
        if (result.outcome() == AgentLogin.Outcome.PREVIEWED) {
            return 0;
        }
        err.println("sokar: " + result.detail());
        err.flush();
        return result.outcome() == AgentLogin.Outcome.FAILED ? 70 : 69;
    }
}
