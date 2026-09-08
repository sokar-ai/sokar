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

        final ProjectInventory.Summary summary = new ProjectInventory(context).projects().stream()
                .filter(candidate -> candidate.name().equals(project)).findFirst().orElse(null);
        if (summary == null) {
            return new Result(Outcome.NO_SUCH_PROJECT, 0, false, "", "no project '" + project
                    + "' is registered here");
        }
        if (summary.mirror() == null) {
            return new Result(Outcome.NO_MIRROR, 0, false, "", "'" + project + "' has never used"
                    + " the gate, so there is no mirror to measure");
        }
        if (summary.file() == null) {
            return new Result(Outcome.UNREADABLE, 0, false, "", "the project file recorded for '"
                    + project + "' is not there any more");
        }
        try {
            final Project read = GateSupport.project(Path.of(summary.file()));
            final UpstreamDistance.Distance distance = UpstreamDistance.measure(context.runner(),
                    Path.of(summary.mirror()), GateMode.of(read.securityClass()));
            // Written where every listing reads it, so a triggered measurement and a timed one
            // leave the same trace and a listing shows the newer of the two without asking.
            new UpstreamRecords(context.paths().upstreamRecords()).put(project, distance);
            return new Result(Outcome.MEASURED, distance.behind(), distance.measured() != null,
                    distance.reason().name(), distance.detail() == null ? "" : distance.detail());
        } catch (RuntimeException ex) {
            return new Result(Outcome.FAILED, 0, false, "", String.valueOf(ex.getMessage()));
        }
    }
}
