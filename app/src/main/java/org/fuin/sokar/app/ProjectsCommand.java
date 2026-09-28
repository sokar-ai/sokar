package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * The projects this machine knows about.
 * <p>
 * The same answer the daemon serves, rendered. What makes it worth a command of its own is the
 * file column: it is where each project's {@code project.yml} was when a task last ran with it,
 * which is the thing nobody can remember across a dozen checkouts.
 * <p>
 * <strong>Verbs, like {@code sokar task}.</strong> Deleting a project existed only over the socket
 * - an interface could do it and a terminal could not, so somebody with only a terminal deleted a
 * directory by hand and went around both refusals doing it. Whatever an interface can do, this can
 * do.
 * <p>
 * {@code projects} stays as a spelling, and running it with no verb still lists, because that is
 * what it did and scripts read it.
 */
@Command(name = "project",
        aliases = { "projects" },
        mixinStandardHelpOptions = true,
        subcommands = { ProjectListCommand.class,
                ProjectFollowCommand.class, ProjectFollowingCommand.class,
                ProjectUnfollowCommand.class },
        description = "Follows project repositories, and lists the projects this machine follows.")
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
        return render(spec.commandLine().getOut());
    }

    /**
     * Renders the listing.
     * <p>
     * Separate from {@link #call()} so the {@code list} verb and the bare command produce the same
     * bytes rather than two renderings that drift.
     *
     * @param out Where to write.
     * @return Exit code.
     */
    Integer render(PrintWriter out) {

        final List<ProjectInventory.Summary> projects = new ProjectInventory(context).projects();

        if (projects.isEmpty()) {
            out.println("No projects yet. One appears here when this machine follows its repository:"
                    + " sokar project follow <name> <url>");
            out.flush();
            return 0;
        }

        final int width = projects.stream().mapToInt(project -> project.name().length()).max()
                .orElse(0);
        for (final ProjectInventory.Summary project : projects) {
            out.printf("%-" + width + "s  %-8s  %s%n", project.name(),
                    project.securityClass() == null ? "-" : project.securityClass(),
                    project.file() == null ? "(file not recorded)" : project.file());
            if (project.repositories().size() > 1) {
                // Only when there is more than one, because one repository is what the line above
                // already reads as. Naming them rather than counting them: this is the list
                // 'sokar task start --repository' takes, and a number would send somebody to the
                // file to find out what to type.
                out.printf("%-" + width + "s  %d repositories: %s%n", "",
                        project.repositories().size(), project.repositories().stream()
                                .map(repository -> repository.name()
                                        // Only where there is something to act on, by the same
                                        // rule the line below follows.
                                        + (repository.pending() > 0
                                                ? " (" + repository.pending() + " waiting)" : ""))
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
            if (project.following() != null && project.following().unverified()) {
                // On the listing and not only under 'project following'. This is the one line that
                // says who decides what tasks here may reach: with no anchor it is whoever can
                // push to that repository, rather than whoever holds the signing key. Somebody
                // reading a list of projects is choosing one to work in, which is exactly the
                // moment that matters.
                out.printf("%-" + width + "s  unverified - whoever can push there decides what"
                        + " tasks here may reach%n", "");
            }
            if (project.pending() > 0 || project.tasks() > 0) {
                // Only when there is something to act on: a line of zeroes under every project is
                // a line nobody reads.
                out.printf("%-" + width + "s  %s%n", "",
                        (project.tasks() > 0 ? project.tasks() + " task"
                                + (project.tasks() == 1 ? "" : "s")
                                + " (" + project.running() + " running)" : "")
                                + (project.pending() > 0 && project.tasks() > 0 ? ", " : "")
                                + (project.pending() > 0 ? project.pending()
                                        + " waiting for review" : ""));
            }
        }
        out.flush();
        return 0;
    }
}
