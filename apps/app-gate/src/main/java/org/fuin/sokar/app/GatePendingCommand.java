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
import org.jspecify.annotations.Nullable;
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
public class GatePendingCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = { "-p", "--project" }, paramLabel = "<name>",
            description = "Project name, as 'sokar project list' prints it. Without it: what waits in every project.")
    private @org.jspecify.annotations.Nullable String projectName;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the project's own.")
    private @Nullable String repository;

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream repository to forward approved pushes to.")
    private @Nullable String upstream;

    @Option(names = "--stale-after", paramLabel = "<hours>",
            description = "Mark pushes older than this. Default: ${DEFAULT-VALUE}")
    private int staleAfterHours = 24;

    @Spec
    private CommandSpec spec;

    /** The fetch for each waiting push listed, said once under the table. */
    private final java.util.List<String> fetches = new java.util.ArrayList<>();

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            if (projectName == null || projectName.isBlank()) {
                // Nothing named: what waits anywhere on this machine. A person new to Sokar asks exactly this, and
                // was answered "missing required option '--project'".
                final java.util.List<String> rows = new java.util.ArrayList<>();
                for (final String name : ProjectSource.names(context)) {
                    rows.addAll(waiting(GateSupport.byName(context, name), true));
                }
                return table(rows, true, "nothing pending in any project", out);
            }
            final Project project = GateSupport.byName(context, projectName);
            if ((repository == null || repository.isBlank()) && DefaultProject.NAME.equals(project.name())) {
                // 'default' has no repository of its own - only the ones worked on without a project - so the
                // question is about all of them. Its placeholder answered "nothing pending" while work waited.
                return table(waiting(project, false), false, "nothing pending in any repository of '"
                        + project.name() + "'", out);
            }
            final GitGate gate = GateSupport.gate(context, project,
                    GateSupport.repository(project, repository), upstream, null);
            gate.initialize();

            final String seededFrom = gate.seededFrom();

            final List<PendingPush> pending = gate.pendingDetail();
            if (pending.isEmpty()) {
                out.println("nothing pending in " + gate.mirror());
                out.println("seeded from " + (seededFrom == null ? "nothing - the mirror was created empty"
                        : seededFrom));
                out.flush();
                return 0;
            }

            final Instant now = Instant.now();
            final Duration stale = Duration.ofHours(staleAfterHours);

            out.println("mode      " + gate.mode().name().toLowerCase());
            out.println("seeded    " + (seededFrom == null ? "nothing" : seededFrom));
            out.printf("%-20s %-10s %-10s %s%n", "NAME", "WAITING", "COMMIT", "SUBJECT");
            for (final PendingPush push : pending) {
                out.printf("%-20s %-10s %-10s %s%s%n", push.name(), push.lagText(now),
                        push.commit(), TalkReadCommand.shown(push.subject()),
                        push.isStale(now, stale) ? "   <- waiting over " + staleAfterHours + "h" : "");
                fetches.add(WaitingFetch.of(gate.mirror(), push.name()));
            }
            fetchesTo(out);
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

    /**
     * Returns what waits in every repository of a project that has a mirror, one row each, creating nothing.
     *
     * @param project The project.
     * @param named Whether each row starts with the project's name.
     * @return The rows.
     */
    private java.util.List<String> waiting(final Project project, final boolean named) {
        final Instant now = Instant.now();
        final Duration stale = Duration.ofHours(staleAfterHours);
        final java.util.List<String> rows = new java.util.ArrayList<>();
        for (final String name : project.repositoryNames()) {
            final org.fuin.sokar.core.project.Repository each = project.repository(name);
            if (each == null) {
                continue;
            }
            final GitGate gate = GateSupport.gate(context, project, each, upstream, null);
            if (!java.nio.file.Files.isDirectory(gate.mirror().resolve("objects"))) {
                // Never made: nothing can wait in it, and asking must not make it.
                continue;
            }
            for (final PendingPush push : gate.pendingDetail()) {
                fetches.add(WaitingFetch.of(gate.mirror(), push.name()));
                rows.add((named ? String.format("%-20s ", project.name()) : "")
                        + String.format("%-20s %-20s %-10s %-10s %s%s", name, push.name(), push.lagText(now),
                                push.commit(), TalkReadCommand.shown(push.subject()), push.isStale(now, stale)
                                        ? "   <- waiting over " + staleAfterHours + "h" : ""));
            }
        }
        return rows;
    }

    private int table(final java.util.List<String> rows, final boolean named, final String none,
            final PrintWriter out) {
        if (rows.isEmpty()) {
            out.println(none);
        } else {
            out.println((named ? String.format("%-20s ", "PROJECT") : "")
                    + String.format("%-20s %-20s %-10s %-10s %s", "REPOSITORY", "NAME", "WAITING", "COMMIT", "SUBJECT"));
            rows.forEach(out::println);
            fetchesTo(out);
        }
        out.flush();
        return 0;
    }

    private void fetchesTo(final PrintWriter out) {
        if (fetches.isEmpty()) {
            return;
        }
        out.println();
        out.println(WaitingFetch.ADVICE);
        fetches.forEach(line -> out.println("  " + line));
    }
}