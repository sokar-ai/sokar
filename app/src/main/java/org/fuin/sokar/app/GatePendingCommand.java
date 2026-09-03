package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GateException;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.gate.PendingPush;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Lists what the agent pushed and nobody has reviewed, with how long it has been waiting.
 * <p>
 * The age is the point. A gate whose queue is never read stops being a control and becomes a
 * queue: work piles up, the operator eventually approves in bulk without reading, and the review
 * was theatre. Showing the lag makes that visible before it becomes a habit.
 */
@Command(name = "pending",
        mixinStandardHelpOptions = true,
        description = "Lists what the agent pushed and how long it has been waiting.")
public class GatePendingCommand implements Callable<Integer> {

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream repository to forward approved pushes to.")
    private String upstream;

    @Option(names = "--stale-after", paramLabel = "<hours>",
            description = "Mark pushes older than this. Default: ${DEFAULT-VALUE}")
    private int staleAfterHours = 24;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            final Project project = GateSupport.project(projectFile);
            final GitGate gate = GateSupport.gate(project, upstream);
            gate.initialise();

            final List<PendingPush> pending = gate.pendingDetail();
            if (pending.isEmpty()) {
                out.println("nothing pending in " + gate.mirror());
                out.flush();
                return 0;
            }

            final Instant now = Instant.now();
            final Duration stale = Duration.ofHours(staleAfterHours);

            out.println("mode      " + gate.mode().name().toLowerCase());
            out.printf("%-20s %-10s %-10s %s%n", "NAME", "WAITING", "COMMIT", "SUBJECT");
            for (final PendingPush push : pending) {
                out.printf("%-20s %-10s %-10s %s%s%n", push.name(), push.lagText(now),
                        push.commit(), push.subject(),
                        push.isStale(now, stale) ? "   <- waiting over " + staleAfterHours + "h" : "");
            }
            out.flush();
            return 0;

        } catch (GateException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }
    }
}
