package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.ContainerName;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Stops a task and everything it started.
 * <p>
 * <strong>The container is stopped, not removed.</strong> Its filesystem is the task's workspace,
 * so removing it would throw away the agent's work and make {@code sokar task resume} impossible.
 * Removal is a separate decision, and a separate command: {@code sokar task remove}.
 * <p>
 * Stopping the container is not enough on its own either: the credential proxy, the git gate and
 * the clearance watcher run on the host. The poststop hook reaps what a container that ran
 * recorded; anything it misses is stopped here, and every helper is checked afterwards rather
 * than assumed to be gone.
 */
@Command(name = "stop",
        mixinStandardHelpOptions = true,
        description = "Stops a task, its container and its helpers.")
public class TaskStopCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Override
    public java.util.List<String> candidates() {
        // Both, unlike attach: stopping a stopped task is answered "nothing to stop" rather
        // than refused, and hiding it as a candidate would make that answer unreachable.
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
    }

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

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // Decided by TaskControl, which the daemon calls too: a refusal that exists in one caller
        // and not the other is a task removed with work in it. This renders, and nothing else.
        final TaskControl.Stopped result =
                new TaskControl(context).stop(container);

        switch (result.outcome()) {
            case NOT_A_TASK -> {
                // Refused rather than guessed at: this stops processes, and a name Sokar did not
                // create belongs to somebody else.
                err.println("sokar: '" + container + "' is not a task Sokar created");
                Suggests.offer(err, this);
                err.flush();
                return 64;
            }
            case NOTHING_TO_STOP -> {
                out.println("No task " + container + "; nothing to stop.");
                out.flush();
                return 0;
            }
            default -> {
                // Stopped, which is everything below.
            }
        }

        out.println("stopped   " + container);
        out.println("helpers   " + (result.helpers() - result.surviving().size())
                + " of " + result.helpers() + " stopped");
        for (final String alive : result.surviving()) {
            out.println("          still running: " + alive);
        }
        if (result.state() != null) {
            out.println("start     sokar task start, to bring it back with the work it has");
            out.println("logs      " + result.state());
        }
        out.flush();
        return result.clean() ? 0 : 70;
    }
}
