package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.shield.EgressSetException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Shows what a project may reach, and changes it.
 * <p>
 * The highest-consequence line in the project file, so both halves are here: what is open and who
 * decided it, and what a change would add or take away <em>in hosts</em> rather than in set names.
 * A set is a name for eight hosts, and an operator adding one is entitled to see the eight.
 * <p>
 * The deciding is {@link EgressControl}'s, which the daemon calls too. This class is the terminal:
 * it turns a request into words and an exit code, and decides nothing.
 */
@Command(name = "egress",
        mixinStandardHelpOptions = true,
        description = "Shows what a project may reach, and adds or removes sets and hosts.")
public class ShieldEgressCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Option(names = "--add-set", paramLabel = "<name>",
            description = "Curated set to declare. Repeatable.")
    private List<String> addSets = new ArrayList<>();

    @Option(names = "--remove-set", paramLabel = "<name>",
            description = "Curated set to stop declaring. Repeatable.")
    private List<String> removeSets = new ArrayList<>();

    @Option(names = "--add-domain", paramLabel = "<host>",
            description = "Host to declare directly, for a mirror no set covers. Repeatable.")
    private List<String> addDomains = new ArrayList<>();

    @Option(names = "--remove-domain", paramLabel = "<host>",
            description = "Host to stop declaring. Repeatable.")
    private List<String> removeDomains = new ArrayList<>();

    @Option(names = "--agent", paramLabel = "<name>",
            description = "Agent whose own grants to include. Default: the only one installed.")
    private String agentName;

    @Option(names = "--dry-run",
            description = "Shows what the change would do and writes nothing.")
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
        final EgressControl control = new EgressControl(context);
        final EgressControl.Change change =
                new EgressControl.Change(addSets, removeSets, addDomains, removeDomains);

        if (change.isEmpty()) {
            return show(control, out, err);
        }
        return change(control.apply(projectFile, change, dryRun), out, err);
    }

    /**
     * Prints what the project may reach today.
     *
     * @param control What answers it.
     * @param out Where to report.
     * @param err Where to report a project file or a set this machine cannot resolve.
     * @return Exit code.
     */
    private int show(EgressControl control, PrintWriter out, PrintWriter err) {
        try {
            final Project project = ProjectReader.read(projectFile);
            out.println("project        " + project.name());
            out.println("security class " + project.securityClass().name().toLowerCase(
                    java.util.Locale.ROOT));
            EgressReport.reportReachable(project, control.reachable(projectFile, agentName),
                    control.refused(agentName), out);
            out.println("declared in    " + projectFile);
            out.flush();
            return 0;
        } catch (ProjectException | EgressSetException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }
    }

    /**
     * Prints what a change did, or why it did nothing.
     *
     * @param effect What the domain decided.
     * @param out Where to report.
     * @param err Where to report a refusal.
     * @return Exit code.
     */
    private int change(EgressControl.Effect effect, PrintWriter out, PrintWriter err) {

        switch (effect.outcome()) {
            case UNREADABLE, REFUSED_BY_CLASS, NO_SUCH_SET -> {
                err.println("sokar: " + effect.detail()
                        + (effect.outcome() == EgressControl.Outcome.NO_SUCH_SET
                                ? ". Run 'sokar shield sets' to see the names." : ""));
                err.flush();
                return 2;
            }
            case NO_CHANGE -> {
                out.println("no change      the file already says that");
                out.flush();
                return 0;
            }
            default -> {
                // Everything below, which all print what the change does first.
            }
        }

        report(effect, out);

        if (effect.cost() != null) {
            out.println("cost           " + effect.cost());
        }
        if (effect.outcome() == EgressControl.Outcome.NOT_WRITTEN) {
            err.println("sokar: " + effect.detail());
            err.flush();
            return 70;
        }
        if (effect.outcome() == EgressControl.Outcome.PREVIEWED) {
            out.println("dry run        nothing was written");
            out.flush();
            return 0;
        }
        out.println("written        " + projectFile);
        // The change applies to the next task. A container's ruleset and resolver are built when
        // it starts and are not reloaded under a running agent.
        out.println("               applies to the next task, not to one already running");
        out.flush();
        return 0;
    }

    /**
     * Prints the hosts a change opens and closes.
     *
     * @param effect What the change does.
     * @param out Where to report.
     */
    private static void report(EgressControl.Effect effect, PrintWriter out) {
        final int width = java.util.stream.Stream.concat(effect.opens().keySet().stream(),
                        effect.closes().keySet().stream())
                .mapToInt(String::length).max().orElse(0);
        String label = "opens";
        for (final Map.Entry<String, String> entry : effect.opens().entrySet()) {
            out.printf("%-14s %-" + width + "s  %s%n", label, entry.getKey(), entry.getValue());
            label = "";
        }
        label = "closes";
        for (final Map.Entry<String, String> entry : effect.closes().entrySet()) {
            out.printf("%-14s %-" + width + "s  %s%n", label, entry.getKey(), entry.getValue());
            label = "";
        }
    }
}
