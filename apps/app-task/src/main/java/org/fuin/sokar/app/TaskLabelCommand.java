package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Gives a task a caption a person can read, beside the name that identifies it.
 * <p>
 * <strong>Not a rename.</strong> A task's name is its identity in four places at once - the
 * container, the gate ref it pushes to, its workspace and its log files - and one of those may
 * have unreviewed pushes behind it. What somebody wants when they ask to rename a task in a list
 * of forty is a caption, and that is what this is.
 * <p>
 * Written into the task's own profile, so it survives a resume and outlives the container.
 */
@Command(name = "label",
        mixinStandardHelpOptions = true,
        description = "Gives a task a caption to read it by. Not a rename.")
public class TaskLabelCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Parameters(index = "0", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'.")
    private String container;

    @Parameters(index = "1", arity = "0..1", paramLabel = "CAPTION",
            description = "What to call it. Leave it out to remove the caption.")
    private @Nullable String caption;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public List<String> candidates() {
        // Every task: a caption is worth as much on a stopped one, which is where a list
        // of forty is hardest to read.
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
    }

    @Override
    public Integer call() {
        // By the container's name or the task's own, as every task command takes it.
        container = TaskTarget.spelled(context, container);

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // The same operation the daemon serves. A caption written by one surface and invisible to
        // the other would be worse than none.
        final TaskControl.Labelled outcome = new TaskControl(context).label(container, caption);

        switch (outcome) {
            case LABELLED -> out.println("labelled  " + container + " \"" + String.valueOf(caption).strip() + "\"");
            case CLEARED -> out.println("cleared   " + container + " shows its own name again");
            case NOT_A_TASK -> {
                err.println("sokar: no task named '" + container + "' - " + Shown.tasks(context));
                err.flush();
                return 69;
            }
            case NOT_RECORDED -> {
                // Started by a Sokar that wrote no profile. Not something an operator can fix by
                // typing it differently, so it says what it is rather than looking like a typo.
                err.println("sokar: " + container + " recorded nothing about itself, so there is"
                        + " nothing to write a caption into; it was started by an older Sokar");
                err.flush();
                return 69;
            }
            case FAILED -> {
                err.println("sokar: could not write " + container + "'s profile");
                err.flush();
                return 70;
            }
        }
        out.flush();
        return 0;
    }
}
