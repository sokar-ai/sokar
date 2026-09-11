package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.ContainerName;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Starts a stopped task again, with the workspace it already has.
 * <p>
 * The container is started rather than rebuilt, so the agent's work, its branch and its uncommitted
 * changes are all still there. What has to be started again is everything that lives on the host:
 * the credential proxy, the git gate and the clearance watcher. None of them can be reconstructed
 * from the container, so each was written down when the task first ran.
 * <p>
 * <strong>The phantom token is adopted, not reissued.</strong> A container's environment is fixed
 * when it is created, so the token the agent holds cannot be changed - a freshly minted one would
 * be rejected and would read as a bad credential.
 */
@Command(name = "resume",
        mixinStandardHelpOptions = true,
        description = "Starts a stopped task again, keeping its workspace.")
public class TaskResumeCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Override
    public java.util.List<String> candidates() {
        // Only the stopped ones: resuming a running task is answered "already up; nothing to
        // resume", and offering it as a candidate would be offering a command that does nothing.
        return TaskCandidates.stopped(context);
    }

    @Override
    public String candidateLabel() {
        return "stopped tasks";
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
        return resume(context, container, spec.commandLine().getOut(),
                spec.commandLine().getErr(), this);
    }

    /**
     * Resumes a task and renders what happened.
     * <p>
     * Static, and taking what it needs, because {@code task attach} resumes a stopped task after
     * asking - and a second rendering of the same outcome would eventually disagree with this
     * one about what a resumed task did. The same reason `TaskControl` decides and this only
     * renders: one behaviour, however many ways in.
     *
     * @param context What to run against.
     * @param container Container name.
     * @param out Where the outcome is written.
     * @param err Where a refusal is written.
     * @param suggests Command to offer names from when the name was wrong, or {@code null}.
     * @return Exit code.
     */
    static int resume(SokarContext context, String container, PrintWriter out, PrintWriter err,
            @org.jspecify.annotations.Nullable Suggests suggests) {

        // Decided by TaskControl, which the daemon calls too. This renders, and nothing else.
        final TaskControl.Resumed result = new TaskControl(context).resume(container);

        switch (result.outcome()) {
            case NOT_A_TASK -> {
                err.println("sokar: '" + container + "' is not a task Sokar created");
                if (suggests != null) {
                    Suggests.offer(err, suggests);
                }
                err.flush();
                return 64;
            }
            case NO_CONTAINER -> {
                err.println("sokar: there is no container " + container + " to resume");
                if (suggests != null) {
                    Suggests.offer(err, suggests);
                }
                err.flush();
                return 69;
            }
            case ALREADY_RUNNING -> {
                out.println("running   " + container + " is already up; nothing to resume");
                if (result.state() != null) {
                    out.println("logs      " + result.state());
                }
                out.flush();
                return 0;
            }
            case PREDATES_RESTART -> {
                err.println("sokar: " + container + " was started before this machine restarted,"
                        + " so it cannot be started again.");
                err.println("       Its sockets were under $XDG_RUNTIME_DIR, which the system"
                        + " clears on restart, and the");
                err.println("       container is bound to one of them. The workspace is still"
                        + " inside it:");
                err.println();
                err.println("         podman cp " + container + ":/workspace ./recovered");
                err.println();
                err.println("       Then discard it with 'sokar task remove " + container
                        + " --force'.");
                err.flush();
                return 69;
            }
            case NO_HELPERS_RECORDED -> {
                out.println("helpers   none recorded, so none were started");
                out.println();
                out.println("This task was started by a Sokar that did not record its helpers,");
                out.println("or recorded them in a format this version no longer reads.");
                out.println("started   " + container + ", with its workspace and nothing else");
                out.flush();
                return 0;
            }
            default -> {
                // Resumed, or resumed with something missing.
            }
        }

        if (result.imageDrift() != null) {
            out.println("image     " + result.imageDrift() + " has been rebuilt since this task"
                    + " started;");
            out.println("          the task keeps the one it has. Start a new task to use");
            out.println("          the new image.");
        }
        out.println("started   " + container);
        out.println("helpers   " + result.started() + " of " + result.recorded() + " started");
        out.println("workspace kept, with everything the agent had done in it");
        if (result.state() != null) {
            out.println("logs      " + result.state());
        }
        // Resume brings the task back up; it does not put anybody inside it, and a person who
        // was told to "go back in" and got their own prompt back reasonably reads that as
        // broken. Saying the next command is cheaper than making this command block.
        out.println("attach    sokar task attach " + container);
        for (final String problem : result.problems()) {
            err.println("sokar: " + problem);
        }
        out.flush();
        err.flush();
        return result.outcome() == TaskControl.Outcome.RESUMED ? 0 : 70;
    }
}
