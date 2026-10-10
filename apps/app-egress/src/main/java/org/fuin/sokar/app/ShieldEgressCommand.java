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
import org.jspecify.annotations.Nullable;
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

    @Option(names = { "-p", "--project" }, paramLabel = "<name>",
            description = "Project name, as 'sokar project list' prints it. Required unless"
                    + " --task says which running task to change, which names its own project.")
    private @Nullable String projectName;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories to declare this for. Default: the"
                    + " project's own. A repository's grants are ADDED to the project's.")
    private @Nullable String repository;

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
    private @Nullable String agentName;

    @Option(names = "--task", paramLabel = "<name>",
            description = "Change what this RUNNING task may reach, rather than the project file."
                    + " Takes effect without stopping it.")
    private @Nullable String task;

    @Option(names = "--also-project",
            description = "With --task: write the same names into the project file as well, so the"
                    + " next task starts with them.")
    private boolean alsoProject;

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
        if (task != null) {
            return widen(task, out, err);
        }
        if (projectName == null) {
            // Required for every path but one: a running task names its own project, and asking
            // for it again would be asking somebody to repeat what the machine already knows.
            err.println("sokar: say which project, with --project - or which running task, with"
                    + " --task.");
            err.flush();
            return 2;
        }
        final Path file = ProjectSource.require(context, projectName);
        return change(file, control.apply(file, repository, change, dryRun), out, err);
    }

    /**
     * Gives a task that is already running somewhere new to reach.
     * <p>
     * A separate path from the file edit because it is a different intention - "this run needs it"
     * rather than "this project needs it" - and the answer says which of the two happened. Only
     * names: a set is a name for several hosts and would need the resolver told about each, which
     * is the same operation repeated rather than a different one, and nobody has asked for it.
     *
     * @param out Where to report.
     * @param err Where to report a refusal.
     * @return Exit code.
     */
    private int widen(String running, PrintWriter out, PrintWriter err) {

        if (!addSets.isEmpty() || !removeSets.isEmpty()) {
            err.println("sokar: --task takes --add-domain and --remove-domain; sets are not"
                    + " granted or taken back one host at a time");
            err.flush();
            return 2;
        }
        if (!addDomains.isEmpty() && !removeDomains.isEmpty()) {
            // Refused rather than ordered. Opening and closing in one call would have to say
            // which happened first, and whichever answer it gave would be somebody's surprise.
            err.println("sokar: give --add-domain or --remove-domain for a running task, not both");
            err.flush();
            return 2;
        }
        if (!removeDomains.isEmpty()) {
            return narrow(running, out, err);
        }

        final RunningEgress.Effect effect = new RunningEgress(context).widen(running, addDomains,
                alsoProject ? RunningEgress.Scope.RUN_AND_PROJECT : RunningEgress.Scope.RUN,
                dryRun);

        switch (effect.outcome()) {
            case NOT_RUNNING, REFUSED_BY_CLASS, REFUSED_NAME, FAILED -> {
                err.println("sokar: " + effect.detail());
                err.flush();
                return effect.outcome() == RunningEgress.Outcome.FAILED ? 70 : 2;
            }
            case NO_CHANGE -> {
                out.println("no change      this run already reaches that");
                out.flush();
                return 0;
            }
            default -> {
                // Widened, previewed, or widened without the file. All three print what they open.
            }
        }

        String label = "opens";
        for (final String name : effect.opens()) {
            out.printf("%-14s %s%n", label, name);
            label = "";
        }
        if (effect.outcome() == RunningEgress.Outcome.PREVIEWED) {
            out.println("dry run        nothing was written");
            out.flush();
            return 0;
        }
        out.println("granted        to " + running + ", from its next attempt");
        // Said every time, because it is what the operator is about to see: the connection that
        // just failed is not retried by Sokar, and the packet that was dropped is gone.
        out.println("               the attempt that was refused is not retried - the agent's next"
                + " one goes through");
        if (effect.file() != null) {
            // The file the task's own project keeps: with --task nobody named a project.
            out.println("written        " + effect.file() + " as well");
        } else if (effect.outcome() == RunningEgress.Outcome.NO_PROJECT_FILE) {
            err.println("sokar: " + effect.detail());
            err.flush();
            out.flush();
            return 0;
        }
        out.flush();
        return 0;
    }

    /**
     * Takes names back from a running task.
     *
     * @param out Where to report.
     * @param err Where to report a refusal.
     * @return Exit code.
     */
    private int narrow(String running, PrintWriter out, PrintWriter err) {

        final RunningEgress.Withdrawal taken = new RunningEgress(context).narrow(running,
                removeDomains,
                alsoProject ? RunningEgress.Scope.RUN_AND_PROJECT : RunningEgress.Scope.RUN,
                dryRun);

        switch (taken.outcome()) {
            case NOT_RUNNING, FAILED -> {
                err.println("sokar: " + taken.detail());
                err.flush();
                return taken.outcome() == RunningEgress.Outcome.FAILED ? 70 : 2;
            }
            case NO_CHANGE -> {
                out.println("no change      this run was not reaching that");
                out.flush();
                return 0;
            }
            default -> {
                // Narrowed, previewed, or narrowed without the file. All three print what closes.
            }
        }

        String label = "closes";
        for (final String name : taken.closes()) {
            out.printf("%-14s %s%n", label, name);
            label = "";
        }
        if (taken.outcome() == RunningEgress.Outcome.PREVIEWED) {
            out.printf("%-14s %d would come out of the firewall%n", "addresses",
                    taken.addresses());
            out.println("dry run        nothing was written");
            out.flush();
            return 0;
        }
        out.printf("%-14s %d removed from the firewall%n", "addresses", taken.addresses());
        // Said every time, because it is the difference between what this did and what somebody
        // may believe it did. A transfer already running is not cut: the ruleset accepts
        // established traffic without consulting the set again.
        out.println("stopped        new connections only - anything already transferring runs to"
                + " its end; stop the task to end that");
        if (taken.file() != null) {
            out.println("written        " + taken.file() + " as well");
        } else if (taken.outcome() == RunningEgress.Outcome.NO_PROJECT_FILE) {
            err.println("sokar: " + taken.detail());
            err.flush();
            out.flush();
            return 0;
        }
        out.flush();
        return 0;
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
        final String name = projectName;
        if (name == null) {
            // Showing is about a project, so there is nothing to show without one.
            err.println("sokar: say which project, with --project.");
            err.flush();
            return 2;
        }
        try {
            final Project project = GateSupport.byName(context, name);
            out.println("project        " + project.name());
            out.println("security class " + project.securityClass().name().toLowerCase(
                    java.util.Locale.ROOT));
            EgressReport.reportReachable(project,
                    control.reachable(ProjectSource.require(context, name), agentName),
                    control.refusals(ProjectSource.require(context, name), null, agentName), out);
            out.println("declared in    " + ProjectSource.require(context, name));
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
     * @param file The project file the change was made to.
     * @param effect What the domain decided.
     * @param out Where to report.
     * @param err Where to report a refusal.
     * @return Exit code.
     */
    private int change(Path file, EgressControl.Effect effect, PrintWriter out, PrintWriter err) {

        switch (effect.outcome()) {
            case UNREADABLE, REFUSED_BY_CLASS, NO_SUCH_SET -> {
                err.println("sokar: " + effect.detail()
                        + (effect.outcome() == EgressControl.Outcome.NO_SUCH_SET
                                ? ". The sets are: " + String.join(", ", context.paths().egress().egressSets().all()
                                        .keySet()) : ""));
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
        out.println("written        " + file);
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
