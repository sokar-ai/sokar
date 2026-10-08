package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Shows what nothing on this machine owns any more, and removes it with {@code --yes}.
 * <p>
 * Without {@code --yes} it removes nothing: deleting is the one thing a person sees first. What holds work - a push
 * nobody reviewed, a stopped task's workspace, a message waiting for a person - is listed and kept unless
 * {@code --including-work} says otherwise.
 */
@Command(name = "prune",
        mixinStandardHelpOptions = true,
        description = "Shows what nothing on this machine owns any more, and removes it with --yes.")
public class PruneCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--yes", description = "Remove what is listed. Without it nothing is removed.")
    private boolean yes;

    @Option(names = "--including-work", description = "Remove what holds work nobody has looked at too: a push"
            + " nobody reviewed, a stopped task's workspace, a message waiting for a person.")
    private boolean includingWork;

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
        final Prune.Result result = new Prune(context).run(yes, includingWork);
        if (!result.found() && result.unchecked().isEmpty()) {
            out.println("Nothing left over: everything here belongs to a project or a task.");
            out.flush();
            return 0;
        }
        final java.util.List<Prune.Item> kept = result.keeps().stream()
                .filter(item -> !item.holds().startsWith("not removed")).toList();
        final java.util.List<Prune.Item> failed = result.keeps().stream()
                .filter(item -> item.holds().startsWith("not removed")).toList();
        if (!result.removes().isEmpty()) {
            out.println(yes ? "Removed:" : "Would be removed - nothing is, without --yes:");
            result.removes().forEach(item -> show(out, item, null));
            out.println();
        }
        if (!kept.isEmpty()) {
            out.println("Kept, because it may hold work nobody has looked at"
                    + (includingWork ? ":" : " - --including-work removes it too:"));
            kept.forEach(item -> show(out, item, item.holds()));
            out.println();
        }
        if (!failed.isEmpty()) {
            out.println("Could not be removed:");
            failed.forEach(item -> show(out, item, item.holds()));
            out.println();
        }
        if (!result.unchecked().isEmpty()) {
            out.println("Not looked at:");
            result.unchecked().forEach(said -> out.println("  " + said));
            out.println();
        }
        if (!yes && !result.removes().isEmpty()) {
            out.println("Nothing was removed. 'sokar prune --yes' removes what would be.");
        }
        out.flush();
        return 0;
    }

    /**
     * Shows one thing found: its kind and name on a line of its own, then why it is kept, then what of it there is -
     * one to a line for a project, whose list is long, and on the same line for anything else.
     */
    private static void show(final PrintWriter out, final Prune.Item item, final @org.jspecify.annotations.Nullable
            String why) {
        if ("PROJECT".equals(item.kind())) {
            out.println("  " + label(item));
            if (why != null) {
                out.println("      " + why);
            }
            item.what().forEach(each -> out.println("        " + each));
            return;
        }
        out.println("  " + label(item) + (item.what().isEmpty() ? "" : " - " + String.join(", ", item.what()))
                + (why == null ? "" : " - " + why));
    }

    private static String label(final Prune.Item item) {
        return item.kind().toLowerCase(java.util.Locale.ROOT) + " " + item.name();
    }
}
