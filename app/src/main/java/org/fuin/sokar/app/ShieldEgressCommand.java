package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.shield.EgressSetDirectory;
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
 * Editing goes through the file rather than around it. The declaration lives in {@code
 * project.yml}, gets reviewed in a diff like anything else, and is what a colleague reads to know
 * what a task may reach - so this rewrites those two keys in place and leaves the rest of the file
 * alone.
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

        final Project project;
        final String original;
        try {
            project = ProjectReader.read(projectFile);
            original = Files.readString(projectFile, StandardCharsets.UTF_8);
        } catch (ProjectException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        } catch (IOException ex) {
            err.println("sokar: cannot read " + projectFile + ": " + ex.getMessage());
            err.flush();
            return 2;
        }

        final EgressSetDirectory sets = context.paths().egressSets();

        if (addSets.isEmpty() && removeSets.isEmpty()
                && addDomains.isEmpty() && removeDomains.isEmpty()) {
            return show(project, sets, out, err);
        }
        return change(project, original, sets, out, err);
    }

    /**
     * Prints what the project may reach today.
     *
     * @param project The project.
     * @param sets Where the installed sets are found.
     * @param out Where to report.
     * @param err Where to report a set the project names and this machine does not have.
     * @return Exit code.
     */
    private int show(Project project, EgressSetDirectory sets, PrintWriter out, PrintWriter err) {

        final Map<String, String> declared;
        try {
            declared = EgressReport.projectEgress(project, sets);
        } catch (EgressSetException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        out.println("project        " + project.name());
        out.println("security class " + project.securityClass().name().toLowerCase(
                java.util.Locale.ROOT));

        // The agent and its provider too, through the same composition a run uses: what an
        // operator wants to know is what the task will reach, and half of it is not in this file.
        try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
            final org.fuin.sokar.agent.api.InstalledAgent selected =
                    TaskLaunch.select(agents, agentName);
            final SelectedProvider serving = selected == null ? null
                    : SelectedProvider.choose(context.providers(), selected.definition(), null);
            EgressReport.reportReachable(project,
                    EgressReport.compose(selected, serving, declared).origins(),
                    EgressReport.refused(selected), out);
        } catch (RuntimeException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }
        out.println("declared in    " + projectFile);
        out.flush();
        return 0;
    }

    /**
     * Applies the requested change, after saying what it does.
     *
     * @param project The project as it is now.
     * @param original The file as it is now.
     * @param sets Where the installed sets are found.
     * @param out Where to report.
     * @param err Where to report a refusal.
     * @return Exit code.
     */
    private int change(Project project, String original, EgressSetDirectory sets, PrintWriter out,
            PrintWriter err) {

        final List<String> wantedSets = edited(project.egress().sets(), addSets, removeSets);
        final List<String> wantedDomains =
                edited(project.egress().domains(), addDomains, removeDomains);

        for (final String name : addSets) {
            if (!sets.all().containsKey(name)) {
                // Refused here rather than written and discovered at the next run: the file would
                // name something this machine cannot resolve, and every task would fail on it.
                err.println("sokar: no egress set '" + name + "' is installed."
                        + " Run 'sokar shield sets' to see the names.");
                err.flush();
                return 2;
            }
        }

        final String updated = EgressEdit.withEgress(original, wantedSets, wantedDomains);
        final Project after;
        try {
            // Parsed by the reader that would have to read it later, before anything is written.
            // This is also where an offline project is refused: it may declare no egress at all,
            // and the reader already says so in the words the operator needs.
            after = ProjectReader.read(new StringReader(updated), projectFile.toString());
        } catch (ProjectException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        final Map<String, String> before;
        final Map<String, String> now;
        try {
            before = EgressReport.projectEgress(project, sets);
            now = EgressReport.projectEgress(after, sets);
        } catch (EgressSetException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        report(before, now, out);

        // At the moment it becomes true, not only when a task next starts: this is the edit that
        // turns the gate from a wall into a convention, and it is worth saying while it is being
        // made.
        final String forges = EgressReport.forgeNote(after, now);
        if (forges != null && EgressReport.forgeNote(project, before) == null) {
            out.println("cost           " + forges);
        }

        if (dryRun) {
            out.println("dry run        nothing was written");
            out.flush();
            return 0;
        }
        try {
            Files.writeString(projectFile, updated, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            err.println("sokar: cannot write " + projectFile + ": " + ex.getMessage());
            err.flush();
            return 70;
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
     * @param before Hosts and origins as they are.
     * @param now Hosts and origins as they would be.
     * @param out Where to report.
     */
    private static void report(Map<String, String> before, Map<String, String> now,
            PrintWriter out) {

        final Map<String, String> added = new LinkedHashMap<>(now);
        before.keySet().forEach(added::remove);
        final Map<String, String> removed = new LinkedHashMap<>(before);
        now.keySet().forEach(removed::remove);

        if (added.isEmpty() && removed.isEmpty()) {
            out.println("no change      the file already says that");
            return;
        }
        final int width = java.util.stream.Stream.concat(added.keySet().stream(),
                        removed.keySet().stream())
                .mapToInt(String::length).max().orElse(0);
        String label = "opens";
        for (final Map.Entry<String, String> entry : added.entrySet()) {
            out.printf("%-14s %-" + width + "s  %s%n", label, entry.getKey(), entry.getValue());
            label = "";
        }
        label = "closes";
        for (final Map.Entry<String, String> entry : removed.entrySet()) {
            out.printf("%-14s %-" + width + "s  %s%n", label, entry.getKey(), entry.getValue());
            label = "";
        }
    }

    /**
     * Returns the list with the additions appended and the removals gone.
     * <p>
     * Order is the file's own, and an addition goes at the end: rewriting the order of a list
     * somebody wrote would make the diff say more than the change did.
     *
     * @param current What the file names.
     * @param additions What to add, ignored when already there.
     * @param removals What to take out, ignored when not there.
     * @return The wanted list.
     */
    private static List<String> edited(List<String> current, List<String> additions,
            List<String> removals) {
        final List<String> wanted = new ArrayList<>(current);
        additions.forEach(value -> {
            if (!wanted.contains(value)) {
                wanted.add(value);
            }
        });
        wanted.removeAll(removals);
        return List.copyOf(wanted);
    }
}
