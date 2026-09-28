package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.ContainerName;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Shows what a task's host-side helpers wrote.
 * <p>
 * <strong>These are the node's logs, not the container's.</strong> The gate, the credential
 * broker, the relay, the resolver and the clearance watcher all run on this machine; what the
 * agent itself printed went to whoever was attached. So this is where an operator looks when a
 * task started and then did nothing.
 * <p>
 * <strong>Why it had to exist.</strong> {@code Logs} and {@code Tail} have been on the contract
 * for a while, so an interface on another machine could read these and a person sitting at the
 * machine could not - they had to know the path under {@code $XDG_RUNTIME_DIR}. That is backwards
 * for a tool whose first audience is at the terminal.
 * <p>
 * Which files exist is asked rather than assumed: a task with no gate has no {@code gate.log}, and
 * one run with {@code --clearance off} has no {@code clearance.log}.
 */
@Command(name = "logs",
        mixinStandardHelpOptions = true,
        description = "Shows what a task's helpers on this machine wrote.")
public class TaskLogsCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    /** How often a followed log is re-read. */
    private static final long FOLLOW_INTERVAL = 500;

    @Parameters(index = "0", paramLabel = "TASK",
            description = "Container name, as shown by 'sokar task list'.")
    private String container;

    @Parameters(index = "1", arity = "0..1", paramLabel = "LOG",
            description = "Which log. Leave it out to list what this task has.")
    private @Nullable String log;

    @Option(names = { "-f", "--follow" },
            description = "Keeps printing as the log grows. Ends with Ctrl-C.")
    private boolean follow;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public List<String> candidates() {
        // Every task: a stopped one still has its logs, and reading them is most of why somebody
        // looks at a task that is no longer running.
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
    }

    @Override
    public Integer call() throws IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        if (!ContainerName.isTask(container)) {
            err.println("sokar: '" + container + "' is not a task of this machine");
            Suggests.offer(err, this);
            err.flush();
            return 64;
        }

        final List<TaskInventory.Log> logs = new TaskInventory(context).logs(container);

        // A name of the right shape that names nothing is not a task with no logs. Reported:
        // 'task logs sokar-does-not' answered "either nothing wrote one, or the machine has
        // restarted", which describes a task that exists - about one that never did.
        //
        // Existing OR having logs, not existing alone: a container somebody removed by hand can
        // leave its state directory behind, and those logs are worth reading precisely then.
        final boolean known = context.podman().sokarTasks().stream()
                .anyMatch(task -> task.name().equals(container));
        if (!known && logs.isEmpty()) {
            err.println("sokar: there is no task called " + container);
            Suggests.offer(err, this);
            err.flush();
            return 69;
        }

        if (log == null) {
            if (logs.isEmpty()) {
                // Two different reasons, and neither is an error: a task that started nothing
                // worth logging, or one whose state directory went with a restart.
                out.println("no logs for " + container + " - either nothing wrote one, or the"
                        + " machine has restarted since it ran");
                out.flush();
                return 0;
            }
            out.printf("%-18s %10s  %s%n", "LOG", "BYTES", "CHANGED");
            for (final TaskInventory.Log each : logs) {
                out.printf("%-18s %10d  %s%n", each.name(), each.bytes(), each.at());
                // Only the names that do not say what they hold. The same sentence the interface
                // gets, from the same place - two sources for one line would disagree eventually.
                if (each.what() != null) {
                    out.printf("%-18s %s%n", "", each.what());
                }
            }
            out.println();
            out.println("Read one with 'sokar task logs " + container + " <LOG>'.");
            out.flush();
            return 0;
        }

        // Only a name this task actually has. Not a path check with an exception for '..' - the
        // set of legal answers is known, so anything outside it is refused without reasoning
        // about traversal at all.
        if (logs.stream().noneMatch(each -> each.name().equals(log))) {
            err.println("sokar: " + container + " has no log called " + log);
            if (!logs.isEmpty()) {
                err.println("sokar: it has: " + String.join(", ",
                        logs.stream().map(TaskInventory.Log::name).toList()));
            }
            err.flush();
            return 69;
        }

        final Path file = context.paths().containerState(container).resolve(log);
        long position = print(file, 0, out);
        while (follow) {
            try {
                Thread.sleep(FOLLOW_INTERVAL);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return 0;
            }
            position = print(file, position, out);
        }
        return 0;
    }

    /**
     * Prints what the file has gained, and returns where to carry on from.
     * <p>
     * By byte position rather than by line count: a log an agent is writing to grows between two
     * reads, and counting lines would reprint whatever arrived in between.
     *
     * @param file The log.
     * @param from Where the last read stopped.
     * @param out Where to write.
     * @return The new position.
     * @throws IOException If the log cannot be read.
     */
    static long print(Path file, long from, PrintWriter out) throws IOException {
        if (!Files.isRegularFile(file)) {
            return from;
        }
        final long size = Files.size(file);
        if (size < from) {
            // Shorter than it was: something truncated or replaced it, so start again rather than
            // seek past the end and print nothing for ever.
            return print(file, 0, out);
        }
        if (size == from) {
            // Not a correctness guard, and no test can tell it from its absence: reading zero
            // bytes prints nothing either way. It is here so that following an idle log does not
            // open and close a channel twice a second for as long as somebody watches it.
            return from;
        }
        try (var channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.READ)) {
            channel.position(from);
            final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate((int) (size - from));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                continue;
            }
            out.print(new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8));
            out.flush();
            return from + buffer.position();
        }
    }
}
