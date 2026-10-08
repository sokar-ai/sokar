package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GateMode;
import org.fuin.sokar.gate.UpstreamDistance;

/**
 * Measures one project's distance from its upstream, now, because somebody asked.
 * <p>
 * <strong>Its own operation, and that is the whole design.</strong> The distance is otherwise
 * measured on a timer and read from a record, because measuring reaches the network and a listing
 * that fetched would make the queue cost what a listing must not - there is an architecture rule
 * enforcing it. So triggering one cannot be a flag on a read; it has to be something somebody
 * asked for.
 * <p>
 * It goes through the same measurement the timer uses and writes the same record, so a triggered
 * fetch and a timed one cannot disagree about how far behind a project is.
 */
public final class UpstreamSync {

    /** What happened. */
    public enum Outcome {

        /** Measured, and the record updated. */
        MEASURED,

        /** No project of that name here. */
        NO_SUCH_PROJECT,

        /** The project has never used the gate, so there is no mirror to measure against. */
        NO_MIRROR,

        /** The project file cannot be read, so its security class is unknown. */
        UNREADABLE,

        /** The measurement itself failed - offline, or the upstream refused. */
        FAILED
    }

    /**
     * What a sync did.
     *
     * @param outcome What happened.
     * @param behind Commits the upstream has that the mirror does not. Meaningless unless
     *        {@code measured}.
     * @param measured Whether the number means anything.
     * @param reason What the measurement reported: MEASURED, NEVER_CHECKED, NO_UPSTREAM, OFFLINE
     *        or FAILED.
     * @param detail Why it failed, or "".
     */
    public record Result(Outcome outcome, int behind, boolean measured, String reason,
            String detail) { }

    private UpstreamSync() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Measures now and records what it found.
     *
     * @param context The machine.
     * @param project Project name, as {@code Projects} reports it.
     * @return What happened.
     */
    public static Result sync(SokarContext context, String project) {
        return sync(context, project, null);
    }

    /**
     * Measures one repository's distance from its own upstream, now.
     * <p>
     * <strong>Per repository, because a project's distance was never one number</strong> once it
     * had a second repository. Each has its own mirror and its own upstream, and reporting the
     * first one's as the project's would be a number that is right about one repository and shown
     * against all of them.
     *
     * @param context The machine.
     * @param project Project name, as {@code Projects} reports it.
     * @param repository Which of its repositories, or {@code null} for the project's own.
     * @return What happened.
     */
    public static Result sync(SokarContext context, String project,
            @org.jspecify.annotations.Nullable String repository) {

        final ProjectInventory.Summary summary = new ProjectInventory(context).projects().stream()
                .filter(candidate -> candidate.name().equals(project)).findFirst().orElse(null);
        if (summary == null) {
            return new Result(Outcome.NO_SUCH_PROJECT, 0, false, "", "no project '" + project
                    + "' is registered here");
        }
        if (summary.file() == null) {
            return new Result(Outcome.UNREADABLE, 0, false, "", "the project file recorded for '"
                    + project + "' is not there any more");
        }
        final String wanted = repository == null || repository.isBlank() ? project : repository;
        final ProjectInventory.RepositorySummary chosen = summary.repositories().stream()
                .filter(candidate -> candidate.name().equals(wanted)).findFirst().orElse(null);
        if (chosen == null) {
            return new Result(Outcome.NO_SUCH_PROJECT, 0, false, "", "project '" + project
                    + "' has no repository '" + wanted + "'");
        }
        if (chosen.mirror().isEmpty()) {
            return new Result(Outcome.NO_MIRROR, 0, false, "", "'" + wanted + "' has never used"
                    + " the gate, so there is no mirror to measure");
        }
        try {
            final Project read = GateSupport.project(Path.of(summary.file()));
            final UpstreamDistance.Distance distance = UpstreamDistance.measure(context.runner(),
                    Path.of(chosen.mirror()), GateMode.of(read.securityClass()),
                    GateSupport.credentials(context, project, wanted));
            // Written where every listing reads it, so a triggered measurement and a timed one
            // leave the same trace and a listing shows the newer of the two without asking.
            new UpstreamRecords(context.paths().projects().upstreamRecords())
                    .put(GateSupport.recordKey(project, wanted), distance);
            return new Result(Outcome.MEASURED, distance.behind(), distance.measured() != null,
                    distance.reason().name(), distance.detail() == null ? "" : distance.detail());
        } catch (RuntimeException ex) {
            return new Result(Outcome.FAILED, 0, false, "", String.valueOf(ex.getMessage()));
        }
    }
}
