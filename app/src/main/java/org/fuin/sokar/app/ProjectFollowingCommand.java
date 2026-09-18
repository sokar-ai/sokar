package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Says which projects this account takes its configuration from, and how that is going.
 * <p>
 * The state is per account and not per machine: two people here follow different projects and
 * reconcile at different moments, because each reaches their own vault.
 */
@Command(name = "following",
        mixinStandardHelpOptions = true,
        description = "Lists the project repositories this account follows.")
public class ProjectFollowingCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final var followed = new FollowedProjects(context.paths().followed()).all();
        if (followed.isEmpty()) {
            out.println("this account follows no project repository");
            out.println("start with: sokar projects follow <name> <url>");
            out.flush();
            return 0;
        }
        out.printf("%-20s %-12s %-10s %s%n", "PROJECT", "COMMIT", "LAST", "WHERE");
        for (final var one : followed) {
            out.printf("%-20s %-12s %-10s %s%n", one.name(),
                    one.commit().isEmpty() ? "-" : one.commit().substring(0, 12),
                    one.outcome().isEmpty() ? "never" : one.outcome().toLowerCase(
                            java.util.Locale.ROOT),
                    one.url());
            if (!one.detail().isEmpty()) {
                out.println("    " + one.detail());
            }
        }
        out.flush();
        return 0;
    }
}
