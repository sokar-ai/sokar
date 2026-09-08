package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.ContainerName;
import org.fuin.sokar.runtime.ContainerSummary;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Opens a shell inside a running task, in a session that survives leaving it.
 * <p>
 * <strong>This is what an interface runs over ssh.</strong> varlink is one call in and many
 * replies out - there is no way for a client to keep sending into an open call - so a session
 * cannot be a method. ssh is the byte pipe varlink is not, and a client that already forwards the
 * daemon socket already has an ssh connection to multiplex a second channel on. Running Sokar's
 * own verb rather than the runtime's command is what keeps the client from learning which
 * container runtime is underneath, and lets Sokar refuse before anything is exec'd.
 * <p>
 * <strong>Leaving does not end it.</strong> The session is a multiplexer inside the container, not
 * a process on the channel, so closing the window leaves it running and the next attachment finds
 * it as it was, with the last {@value org.fuin.sokar.runtime.Containerfile#SCROLLBACK} lines it
 * printed. That figure is pinned in the image rather than inherited, because what re-entering may
 * claim has to be a number somebody chose. It ends with the container: {@code task stop} takes it, and {@code task resume}
 * brings back an empty one.
 * <p>
 * <strong>No refusal by security class.</strong> The class governs egress - what resolves and what
 * leaves - and a person typing in a container is neither. An offline project is precisely the one
 * where somebody has to work by hand, because the agent reaches nothing.
 */
@Command(name = "attach",
        mixinStandardHelpOptions = true,
        description = "Opens a shell in a running task. Leaving it does not end it.")
public class TaskAttachCommand implements Callable<Integer>, SokarFactory.ContextAware {

    /** Name of the session inside the container. One per container, found again on return. */
    static final String SESSION = "sokar";

    @Parameters(index = "0", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'.")
    private String container;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Returns the command that attaches to the session, creating it if there is none.
     * <p>
     * {@code new-session -A} is attach-or-create in one call, so returning to a task and starting
     * one for the first time are the same operation - which is what makes "coming back" reliable
     * rather than a second code path that is exercised less.
     *
     * @return Program and arguments, to run inside the container.
     */
    static java.util.List<String> sessionCommand() {
        // The configuration file is read explicitly rather than left to tmux's search: what a
        // session remembers is the answer to "what may re-entering claim", and inheriting it from
        // whatever the image or a user's dotfile happened to say would make that answer unknown
        // to the one process that has to state it.
        return java.util.List.of("tmux", "-f", "/etc/sokar/tmux.conf",
                "new-session", "-A", "-s", SESSION);
    }

    @Override
    public Integer call() {

        final PrintWriter err = spec.commandLine().getErr();

        if (!ContainerName.isSokar(container)) {
            err.println("sokar: '" + container + "' is not a task of this machine");
            err.flush();
            return 69;
        }
        final boolean running = context.podman().sokarTasks().stream()
                .filter(task -> task.name().equals(container))
                .anyMatch(ContainerSummary::running);
        if (!running) {
            // Named as the two different things it can be. "Not running" sends somebody to
            // 'task resume'; "no such task" sends them to 'task list'.
            final boolean known = context.podman().sokarTasks().stream()
                    .anyMatch(task -> task.name().equals(container));
            err.println(known
                    ? "sokar: " + container + " is not running - 'sokar task resume " + container
                            + "' brings it back with the workspace it has"
                    : "sokar: no task called " + container + " - 'sokar task list' shows what"
                            + " is there");
            err.flush();
            return 69;
        }

        // Replaces this process with the session, which is why the arguments are returned rather
        // than run: what is on the far end of the ssh channel has to be the terminal itself.
        return context.exec().applyAsInt(
                context.podman().attachArguments(container, sessionCommand()));
    }
}
