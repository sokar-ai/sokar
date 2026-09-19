package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Stops taking a project's configuration from its repository, and removes the project.
 * <p>
 * <strong>It deletes, and that is why it asks first.</strong> A mirror may hold pushes nobody has
 * reviewed and tasks may hold work; removing those silently would destroy exactly what the gate
 * exists to protect, by a command whose name sounds like unsubscribing from something. So the
 * refusal and the {@code --force} path are the ones {@code projects delete} already uses, through
 * the same code - a second implementation of "is there work in the way" is a second chance to get
 * it wrong.
 */
@Command(name = "unfollow",
        mixinStandardHelpOptions = true,
        description = "Stops following a project's repository, and removes the project.")
public class ProjectUnfollowCommand
        implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Override
    public java.util.List<String> candidates() {
        // Every project the machine LISTS, not only the followed ones - the same list the refusal
        // prints, which is the rule completion follows everywhere here. A project that predates
        // following is exactly the one somebody cannot remember the spelling of.
        return new ProjectInventory(context).projects().stream()
                .map(ProjectInventory.Summary::name).toList();
    }

    @Override
    public String candidateLabel() {
        return "projects";
    }

    @Parameters(index = "0", paramLabel = "<name>", description = "The project.")
    private String name;

    @Option(names = "--dry-run", description = "Says what would go, and removes nothing.")
    private boolean dryRun;

    @Option(names = "--force",
            description = "Removes it although work is waiting for review. That work is destroyed.")
    private boolean force;

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
        final PrintWriter err = spec.commandLine().getErr();
        final FollowedProjects projects = new FollowedProjects(context.paths().followed());
        final FollowedProjects.Followed followed = projects.find(name);

        // Asked for every project this machine LISTS, not only for the ones it follows. A project
        // that came to be the old way - a task was started with a file - is in no follow record,
        // and this used to refuse it outright: the project stayed in the listing, kept its mirror
        // and its image, and no command ended it. Two of them were cleared by hand on a test
        // machine, which is not a thing an interface can do. What is at risk is the same either
        // way, so the checks are the same either way.
        //
        // The project first, because that is the part that can refuse. Forgetting the repository
        // and then failing to remove the project would leave a project nothing updates any more -
        // the worst of both.
        final ProjectDeletion.Result deleted =
                new ProjectDeletion(context).delete(name, dryRun, force);

        if (followed == null && deleted.outcome() == ProjectDeletion.Outcome.NO_SUCH_PROJECT) {
            if (force) {
                // With --force this is the one command a cleanup trap can run blind: a trap fires
                // after a failure, which is exactly when nobody knows how far the setup got.
                // Removing nothing is a success, not an error - anything else makes a script
                // guard a command whose whole purpose is to be the guard.
                out.println("nothing of " + name + " is here");
                out.flush();
                return 0;
            }
            // A name this machine does not list at all. Kept distinct from "it is here but you do
            // not follow it", because that distinction is the whole value of the answer.
            final java.util.List<String> known = ProjectSource.names(context);
            err.println("sokar: no project called '" + name + "' here." + (known.isEmpty()
                    ? "" : " This machine has: " + String.join(", ", known)));
            err.flush();
            return 70;
        }
        switch (deleted.outcome()) {
            case HOLDS_WORK -> {
                err.println("sokar: " + name + " holds work nobody has reviewed:");
                deleted.unreviewed().forEach(ref -> err.println("    " + ref));
                err.println("       review it, or 'sokar projects unfollow " + name
                        + " --force' to destroy it");
                err.flush();
                return 70;
            }
            case TASKS_RUNNING -> {
                err.println("sokar: " + name + " still has tasks up:");
                deleted.running().forEach(task -> err.println("    " + task));
                err.println("       stop them first");
                err.flush();
                return 70;
            }
            case NO_SUCH_PROJECT -> {
                // Followed but never applied: there is no project to delete, and forgetting the
                // record is the whole of it.
                out.println("nothing of " + name + " was ever applied here");
            }
            case PREVIEWED -> {
                out.println(followed == null
                        ? "would remove " + name + ", which this account does not follow"
                        : "would remove " + name + " and stop following " + followed.url());
                deleted.removes().forEach(removal -> out.println("    " + removal));
                // What survives, said as plainly as what goes. Somebody deciding whether to
                // confirm is asking both questions at once, and the one nobody answers is the one
                // they assume the worst about.
                deleted.keeps().forEach(kept -> out.println("    kept: " + kept));
                out.flush();
                return 0;
            }
            case DELETED -> deleted.removes().forEach(removal -> out.println("removed  " + removal));
            default -> {
                err.println("sokar: " + deleted.detail());
                err.flush();
                return 70;
            }
        }

        if (followed == null) {
            out.println(name + " was not followed here; what Sokar built for it is gone");
            out.flush();
            return 0;
        }
        deleteTree(context.paths().followedClone(name));
        projects.unfollow(name);
        out.println("no longer following " + followed.url());
        out.flush();
        return 0;
    }

    private static void deleteTree(final java.nio.file.Path root) throws java.io.IOException {
        if (!java.nio.file.Files.exists(root)) {
            return;
        }
        try (var tree = java.nio.file.Files.walk(root)) {
            for (final java.nio.file.Path path
                    : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }
}
