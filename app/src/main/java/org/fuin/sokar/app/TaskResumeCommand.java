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
public class TaskResumeCommand implements Callable<Integer>, SokarFactory.ContextAware {

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
     * Replaces the container pid the watcher was started with, which belongs to the previous run.
     *
     * @param command The recorded command.
     * @param pid The pid the container has now.
     * @return The command to start.
     */
    private static List<String> withPid(List<String> command, long pid) {
        final List<String> updated = new ArrayList<>(command);
        final int flag = updated.indexOf("--pid");
        if (flag >= 0 && flag + 1 < updated.size()) {
            updated.set(flag + 1, String.valueOf(pid));
        }
        return updated;
    }

    /**
     * Adds the flag that makes the proxy keep the token the container already holds.
     *
     * @param command The recorded command.
     * @return The command to start.
     */
    private static List<String> reusingToken(List<String> command) {
        if (command.contains("--reuse-token")) {
            return command;
        }
        final List<String> updated = new ArrayList<>(command);
        updated.add("--reuse-token");
        return updated;
    }

    /**
     * Starts the helpers of one phase.
     *
     * @param recorded What the task wrote down.
     * @param phase Which phase to start.
     * @param pid Container pid, for helpers that need it; ignored when zero.
     * @param state The task's state directory.
     * @param err Where failures are reported.
     * @return How many started.
     */
    private int start(TaskHelpers recorded, String phase, long pid, Path state, PrintWriter err) {

        int started = 0;
        for (final TaskHelpers.Helper helper : recorded.helpers()) {
            if (!phase.equals(helper.phase())) {
                continue;
            }
            List<String> command = helper.command();
            if ("watcher".equals(helper.name()) && pid > 0) {
                command = withPid(command, pid);
            }
            if ("vault".equals(helper.name())) {
                command = reusingToken(command);
            }
            try {
                final ProcessBuilder builder = new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect
                                .appendTo(state.resolve(helper.name() + ".log").toFile()));
                builder.environment().putAll(helper.environment());
                builder.start();
                started++;
            } catch (IOException ex) {
                err.println("sokar: could not start the " + helper.name() + ": " + ex.getMessage());
                err.flush();
            }
        }
        return started;
    }

    /**
     * Waits for the helpers that must be up before the container to write their pid files.
     * <p>
     * Starting the container while the proxy is still binding its socket is the same failure as
     * starting it too early: the mount catches whatever is at the path at that moment.
     *
     * @param recorded What the task wrote down.
     * @param state The task's state directory.
     */
    private void awaitHelpers(TaskHelpers recorded, Path state) {
        final List<Path> expected = recorded.helpers().stream()
                .filter(helper -> TaskHelpers.BEFORE.equals(helper.phase()))
                .map(helper -> state.resolve(helper.name() + ".pid"))
                .toList();
        for (int attempt = 0; attempt < 50; attempt++) {
            if (expected.stream().allMatch(Files::isRegularFile)) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Says so when the project's image has been rebuilt since this task was created.
     * <p>
     * The task deliberately keeps the image it has - everything installed in the container since
     * it started would be lost otherwise, which is the opposite of what resuming is for. The
     * operator is told rather than upgraded, because only they know whether the difference matters.
     *
     * @param out Where to report.
     */
    private void warnAboutImageDrift(PrintWriter out) {
        context.podman().imageOf(container).ifPresent(image -> {
            final String name = image[0];
            final String was = image[1];
            context.podman().imageId(name).ifPresent(now -> {
                if (!now.equals(was)) {
                    out.println("image     " + name + " has been rebuilt since this task started;");
                    out.println("          the task keeps the one it has. Start a new task to use");
                    out.println("          the new image.");
                }
            });
        });
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        if (!ContainerName.isSokar(container)) {
            err.println("sokar: '" + container + "' is not a task Sokar created");
            err.flush();
            return 64;
        }

        final Path state = context.paths().containerState(container);
        final TaskHelpers recorded = TaskHelpers.readFrom(state);

        if (context.podman().idOf(container).isEmpty()) {
            err.println("sokar: there is no container " + container + " to resume");
            err.flush();
            return 69;
        }

        if (recorded.helpers().isEmpty()) {
            out.println("helpers   none recorded, so none were started");
            out.println();
            out.println("This task was started by a Sokar that did not record its helpers,");
            out.println("or recorded them in a format this version no longer reads.");
            context.podman().start(container);
            out.println("started   " + container + ", with its workspace and nothing else");
            out.flush();
            return 0;
        }

        warnAboutImageDrift(out);

        // The proxy's socket is mounted into the container, and a mount is bound to the file that
        // existed when the container started. Starting the container first therefore binds it to
        // the socket of the previous run, which the resumed proxy then replaces: measured, the
        // container held a deleted inode and every request through it went nowhere, while the same
        // request from the host was answered.
        int started = start(recorded, TaskHelpers.BEFORE, 0, state, err);
        awaitHelpers(recorded, state);

        context.podman().start(container);
        out.println("started   " + container);

        final long pid = context.podman().pidOf(container).orElse(0L);
        if (pid <= 0) {
            err.println("sokar: the container did not come up; see " + state);
            err.flush();
            return 70;
        }

        started += start(recorded, TaskHelpers.AFTER, pid, state, err);
        out.println("helpers   " + started + " of " + recorded.helpers().size() + " started");
        out.println("workspace kept, with everything the agent had done in it");
        if (Files.isDirectory(state)) {
            out.println("logs      " + state);
        }
        out.flush();
        return started == recorded.helpers().size() ? 0 : 70;
    }
}
