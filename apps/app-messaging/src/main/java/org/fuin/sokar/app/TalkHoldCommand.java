package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Holds a peer or releases it, and says how closely its project watches it.
 * <p>
 * The hold is the project's, for every task of it, and lives on the host: a task must not be able to change how
 * closely it is watched. The mode is not set here: the project's {@code project.yml} sets it once, for every task.
 */
@Command(name = "hold",
        mixinStandardHelpOptions = true,
        description = "Holds a peer or releases it, for every task of the project.")
public class TalkHoldCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Parameters(index = "1", paramLabel = "<peer>", description = "The peer to decide about.")
    private String peer;

    @Option(names = "--release", description = "Stops holding everything for this peer.")
    private boolean release;

    @Option(names = "--mode", paramLabel = "<mode>", hidden = true,
            description = "Refused, and says where a mode is set now: the project's project.yml.")
    private @Nullable String mode;

    @Option(names = { "-p", "--project" }, paramLabel = "<name>",
            description = "The project, which decides whether unread work may leave, as 'sokar"
                    + " project list' prints it. Default: the task's own.")
    private @Nullable String projectName;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        // By the container's name or the task's own, as every task command takes it.
        container = TaskTarget.spelled(context, container);
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        if (!mailbox.exists()) {
            err.println("sokar: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        // A name, like every other command since projects stopped being pointed at by file; this one
        // still read project.yml from wherever it was run, which was nowhere a task's project lives.
        final String name = projectName != null ? projectName : new TaskInventory(context).projectOf(container);
        if (name == null) {
            err.println("sokar: " + container + " names no project; say which, with --project");
            err.flush();
            return 2;
        }
        final Project read = GateSupport.byName(context, name);
        final Moderation moderation = Moderation.of(context, read);
        // Bare, it holds; with --release it releases. A --mode is answered with where a mode is set now.
        final Boolean held = release ? Boolean.FALSE : mode == null ? Boolean.TRUE : null;
        final Moderation.Change change = moderation.set(peer, held, mode, read);
        if (change.peer() == null) {
            err.println("sokar: " + change.refused());
            err.flush();
            return 1;
        }
        out.println(peer + "   mode " + change.peer().mode()
                + (change.peer().held() ? ", held" : ", not held"));
        out.flush();
        return 0;
    }
}
