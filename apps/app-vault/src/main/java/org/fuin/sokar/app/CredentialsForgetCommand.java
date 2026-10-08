package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Forgets a record, and says what still holds a value.
 * <p>
 * It does not remove a secret. A key in somebody's own directory is theirs, and a vault entry is
 * removed by a person who says so - {@code vault remove}. Naming what is left is how somebody can
 * take the second step instead of believing it was already taken.
 */
@Command(name = "forget",
        mixinStandardHelpOptions = true,
        description = "Forgets a credential record. The value itself is left alone.")
public class CredentialsForgetCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<match>",
            description = "The destinations it covers, as 'sokar credentials list' shows them.")
    private String match;

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
        final String left;
        try {
            left = new CredentialDeclarations(context).forget(match);
        } catch (final java.io.IOException ex) {
            err.println("sokar: cannot write the record: " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (left == null) {
            err.println("sokar: nothing is declared for '" + match + "'");
            err.flush();
            return 70;
        }
        out.println("forgot     " + match);
        if (!left.isEmpty()) {
            out.println("           " + left);
        }
        out.flush();
        return 0;
    }
}
