package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
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
    private String agentName;

    @Option(names = "--dry-run", description = "Says what it would run and runs nothing.")
    private boolean dryRun;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final AgentLogin.Result result = AgentLogin.login(context, agentName, dryRun, out);
        out.flush();

        if (result.outcome() == AgentLogin.Outcome.STORED) {
            // The value is never echoed: what is useful is that it arrived and which kind it is.
            out.println("stored    " + result.name() + " (" + result.type() + ", "
                    + result.length() + " characters)");
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
