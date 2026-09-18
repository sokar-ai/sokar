package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Holds a peer, releases it, or says how closely it is watched.
 * <p>
 * The state is per mailbox and lives on the host: a task must not be able to change how closely it
 * is watched, and a person holding a peer is holding this task's conversations with it, not
 * everybody's.
 */
@Command(name = "hold",
        mixinStandardHelpOptions = true,
        description = "Holds a peer, releases it, or sets how much is asked.")
public class TalkHoldCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Parameters(index = "1", paramLabel = "<peer>", description = "The peer to decide about.")
    private String peer;

    @Option(names = "--release", description = "Stops holding everything for this peer.")
    private boolean release;

    @Option(names = "--mode", paramLabel = "<mode>",
            description = "prompt, allow, deny or off. Default: leaves it as it is.")
    private String mode;

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file, which decides whether unread work may leave.")
    private Path project = Path.of("project.yml");

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Mailbox mailbox = new Mailbox(context.paths().mailbox(container));
        if (!mailbox.exists()) {
            err.println("sokar: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        final Project read = ProjectReader.read(project);
        final Moderation moderation = new Moderation(mailbox);
        // Bare, it holds; with --release it releases; with only --mode it changes the mode and
        // leaves the hold as it was, because "set this to prompt" is not "and hold it too".
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
