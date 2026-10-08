package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/** Lists what this machine is configured to connect out with. */
@Command(name = "list",
        mixinStandardHelpOptions = true,
        description = "Lists the credentials this machine connects out with.")
public class CredentialsListCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        return render(context, spec.commandLine().getOut());
    }

    /**
     * Renders the listing.
     *
     * @param context The machine.
     * @param out Where to write.
     * @return Exit code.
     */
    Integer render(final SokarContext context, final PrintWriter out) {
        final List<Map<String, Object>> rows = new CredentialDeclarations(context).asMaps();
        if (rows.isEmpty()) {
            out.println("Nothing is declared. A git URL still finds a vault entry named after its"
                    + " host - 'sokar credentials check <url>' says what one would use.");
            out.flush();
            return 0;
        }
        final int width = rows.stream().mapToInt(row -> String.valueOf(row.get("match")).length())
                .max().orElse(0);
        for (final Map<String, Object> row : rows) {
            out.printf("%-" + width + "s  %-8s  %-11s  %s%n", row.get("match"), row.get("kind"),
                    row.get("source"),
                    (Boolean.TRUE.equals(row.get("present")) ? row.get("id")
                            : row.get("id") + "  (its value is not there)"));
            if (!Boolean.TRUE.equals(row.get("protected"))) {
                // Said every time, because it is the difference between a secret this machine can
                // shut away and one it merely knows where to find.
                out.printf("%-" + width + "s  this machine does not protect it%n", "");
            }
        }
        out.flush();
        return 0;
    }
}
