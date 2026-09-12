package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Removes what Sokar built for a project.
 * <p>
 * <strong>Not the project.</strong> The project file, the checkout and the real upstream are the
 * operator's and are not touched. What goes is the gate mirror, the task image, the build
 * directory, the registry entry and every task with it - all of which a later run rebuilds, which
 * is what makes this safe to offer.
 * <p>
 * It refuses rather than decides, in three ways, and every one of them is a question rather than
 * an error: work reached the gate and nobody reviewed it, tasks are still up, or a task would not
 * give up what it holds.
 */
@Command(name = "delete",
        mixinStandardHelpOptions = true,
        description = "Removes what Sokar built for a project, keeping the project file.")
public class ProjectDeleteCommand implements Callable<Integer>, SokarFactory.ContextAware,
        Suggests {

    @Parameters(index = "0", paramLabel = "PROJECT",
            description = "Project name, as shown by 'sokar project list'.")
    private String project;

    @Option(names = "--dry-run",
            description = "Says what would go and removes nothing.")
    private boolean dryRun;

    @Option(names = "--force",
            description = "Removes it despite unreviewed work or running tasks.")
    private boolean force;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public List<String> candidates() {
        return new ProjectInventory(context).projects().stream()
                .map(ProjectInventory.Summary::name).sorted().toList();
    }

    @Override
    public String candidateLabel() {
        return "projects";
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // The same class the daemon calls. A refusal that exists on one surface and not the other
        // is what sent somebody to delete a directory by hand, around both guards.
        final ProjectDeletion.Result result = new ProjectDeletion(context).delete(project, dryRun,
                force);

        // The word follows the OUTCOME, not the --dry-run flag, and that is the whole point of
        // doing it here rather than in the loop below. A refusal used to print "removed mirror",
        // "removed build", "removed task ..." and then "nothing has been removed" three lines
        // later, with the project still in the listing afterwards. Found while clearing a test
        // machine on 2026-09-12: five lines claiming a removal, one saying none happened, and the
        // reader has to decide which to believe.
        //
        // On a refusal the list is exactly what a preview would have shown - what it WOULD have
        // taken - which is the useful thing to print beside the reason it stopped.
        final boolean happened = result.outcome() == ProjectDeletion.Outcome.DELETED;
        for (final ProjectDeletion.Removal removal : result.removes()) {
            out.println((happened ? "removed   " : "would remove ") + removal.kind().toLowerCase(
                    java.util.Locale.ROOT) + "  " + removal.what());
        }
        for (final String kept : result.keeps()) {
            out.println("keeps     " + kept);
        }

        switch (result.outcome()) {
            case NO_SUCH_PROJECT -> {
                err.println("sokar: nothing here knows a project called '" + project + "'");
                err.flush();
                return 64;
            }
            case HOLDS_WORK, TASKS_RUNNING -> {
                err.println("sokar: " + result.detail());
                err.flush();
                return 65;
            }
            case FAILED -> {
                err.println("sokar: " + result.detail());
                err.flush();
                return 70;
            }
            case PREVIEWED -> {
                out.println("Nothing was removed.");
                out.flush();
                return 0;
            }
            default -> {
                out.println("deleted   " + project);
                out.flush();
                return 0;
            }
        }
    }
}
