package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Follows a project now, at a person's word, rather than at the next round: right after a push to its repository, or
 * after something that kept it from being fetched was fixed.
 */
@Command(name = "refresh",
        mixinStandardHelpOptions = true,
        description = "Fetches a followed project's repository now, or every one's, and says what it found.")
public class ProjectRefreshCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<project>",
            description = "The project. Default: every project this account follows.")
    private @Nullable String project;

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
        final Map<String, Reconcile.Result> done = new ConfigurationWatch(context, Duration.ZERO).refresh(project);
        if (done == null) {
            err.println("sokar: this account follows no project '" + project + "'; 'sokar project following' lists"
                    + " the ones it does");
            err.flush();
            return 66;
        }
        boolean failed = false;
        for (final Map.Entry<String, Reconcile.Result> each : done.entrySet()) {
            final Reconcile.Result result = each.getValue();
            out.printf("%-20s %-12s %s%n", each.getKey(),
                    result.commit().isEmpty() ? "-" : result.commit().substring(0, Math.min(12, result.commit().length())),
                    result.outcome().name().toLowerCase(java.util.Locale.ROOT));
            if (!result.detail().isEmpty()) {
                out.println("    " + result.detail());
            }
            failed |= !ConfigurationWatch.fetched(result) && result.outcome() != Reconcile.Outcome.FROM_A_FILE;
        }
        out.flush();
        return failed ? 70 : 0;
    }
}
