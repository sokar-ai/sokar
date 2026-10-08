package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Says which credential a destination would use, without connecting to it. */
@Command(name = "check",
        mixinStandardHelpOptions = true,
        description = "Says which credential a URL would use, and whether it would work.")
public class CredentialsCheckCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<url>", description = "Where something would connect.")
    private String url;

    @Option(names = "--purpose", paramLabel = "<what>",
            description = "What for. Default: ${DEFAULT-VALUE}")
    private String purpose = "git";

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
        final CredentialDeclarations.Check check =
                new CredentialDeclarations(context).check(url, purpose);
        if (check.outcome() == CredentialDeclarations.Outcome.READY
                || check.outcome() == CredentialDeclarations.Outcome.NOT_NEEDED) {
            out.println(check.outcome().name().toLowerCase(java.util.Locale.ROOT)
                    + "      " + check.detail());
            out.flush();
            return 0;
        }
        err.println(check.outcome().name().toLowerCase(java.util.Locale.ROOT)
                + "      " + check.detail());
        if (!check.storeCommand().isEmpty()) {
            err.println("           " + check.storeCommand());
        }
        err.flush();
        return 70;
    }
}
