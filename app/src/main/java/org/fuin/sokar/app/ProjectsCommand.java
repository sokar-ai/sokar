package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Lists the projects this machine knows about.
 * <p>
 * The same answer the daemon serves, rendered. What makes it worth a command of its own is the
 * file column: it is where each project's {@code project.yml} was when a task last ran with it,
 * which is the thing nobody can remember across a dozen checkouts.
 */
@Command(name = "projects",
        mixinStandardHelpOptions = true,
        description = "Lists the projects this machine has run tasks for.")
public class ProjectsCommand implements Callable<Integer>, SokarFactory.ContextAware {

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
        final List<ProjectInventory.Summary> projects = new ProjectInventory(context).projects();

        if (projects.isEmpty()) {
            out.println("No projects yet. One appears here the first time you run a task with it.");
            out.flush();
            return 0;
        }

        final int width = projects.stream().mapToInt(project -> project.name().length()).max()
                .orElse(0);
        for (final ProjectInventory.Summary project : projects) {
            out.printf("%-" + width + "s  %-8s  %s%n", project.name(),
                    project.securityClass() == null ? "-" : project.securityClass(),
                    project.file() == null ? "(file not recorded)" : project.file());
            if (project.pending() > 0 || project.tasks() > 0) {
                // Only when there is something to act on: a line of zeroes under every project is
                // a line nobody reads.
                out.printf("%-" + width + "s  %s%n", "",
                        (project.tasks() > 0 ? project.tasks() + " task"
                                + (project.tasks() == 1 ? "" : "s") : "")
                                + (project.pending() > 0 && project.tasks() > 0 ? ", " : "")
                                + (project.pending() > 0 ? project.pending()
                                        + " waiting for review" : ""));
            }
        }
        out.flush();
        return 0;
    }
}
