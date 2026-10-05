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
 * Lists the repositories of the project {@code default}, adds one by its address, or takes one out.
 * <p>
 * A repository usually comes into {@code default} by starting a task in its checkout with no project named. Adding one
 * by address is the same for a repository not checked out here; taking one out leaves its mirror, so nothing waiting
 * for review is lost.
 */
@Command(name = "default",
        mixinStandardHelpOptions = true,
        description = "Lists the repositories of the project 'default', adds one by address, or takes one out.")
public class ProjectDefaultCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<action>", description = "list (the default), add or"
            + " remove.")
    private String action = "list";

    @Parameters(index = "1", arity = "0..1", paramLabel = "<address-or-name>",
            description = "With add, the repository's address; with remove, its name in 'default'.")
    private @Nullable String argument;

    @Option(names = "--name", paramLabel = "<name>", description = "With add: what to call it. Default: after its"
            + " address.")
    private @Nullable String name;

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
        final DefaultProject project = new DefaultProject(context);
        switch (action) {
            case "list" -> {
                if (project.entries().isEmpty()) {
                    out.println("'" + DefaultProject.NAME + "' has no repository yet: start a task in a checkout, or"
                            + " 'sokar project default add <address>'");
                }
                for (final DefaultProject.Entry entry : project.entries()) {
                    final WorkOrigin.Choice claimed = WorkOrigin.followedNaming(context, entry.upstream());
                    out.printf("%-20s %s%s%n", entry.name(), entry.upstream(), claimed == null ? ""
                            : "  (" + claimed.said() + "; its tasks start there - remove it here)");
                }
                out.flush();
                return 0;
            }
            case "add" -> {
                if (argument == null) {
                    err.println("sokar: add needs the repository's address");
                    err.flush();
                    return 64;
                }
                try {
                    final DefaultProject.Entry entry = project.add(argument, name, "");
                    out.println("in '" + DefaultProject.NAME + "' as " + entry.name() + "  " + entry.upstream());
                    out.flush();
                    return 0;
                } catch (DefaultProject.Refused ex) {
                    err.println("sokar: " + ex.getMessage());
                    err.flush();
                    return 64;
                }
            }
            case "remove" -> {
                if (argument == null) {
                    err.println("sokar: remove needs the repository's name in '" + DefaultProject.NAME + "'");
                    err.flush();
                    return 64;
                }
                final java.util.List<DeployKeys.Key> forgotten = project.remove(argument);
                if (forgotten == null) {
                    err.println("sokar: '" + DefaultProject.NAME + "' has no repository called '" + argument + "'");
                    err.flush();
                    return 70;
                }
                out.println("removed  " + argument + " from '" + DefaultProject.NAME + "'; its mirror and anything"
                        + " waiting in it stay");
                forgotten.forEach(key -> out.println("forgot   deploy key '" + key.title() + "' " + key.fingerprint()
                        + " - remove it at the forge"));
                out.flush();
                return 0;
            }
            default -> {
                err.println("sokar: '" + action + "' is not one of list, add or remove");
                err.flush();
                return 64;
            }
        }
    }
}
