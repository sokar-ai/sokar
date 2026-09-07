package org.fuin.sokar.app;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GateMode;
import org.fuin.sokar.gate.UpstreamDistance;

/**
 * Measures, on a schedule, how far each project has fallen behind its upstream.
 * <p>
 * <strong>Why a timer and not a reply field.</strong> An interface re-reads the project list after
 * anything that could have changed it - a task started, a push approved - so a field that quietly
 * fetched would turn ordinary use into network traffic nothing on screen accounts for, and would
 * make a list occasionally take thirty seconds for reasons nobody can see. An age can be rendered.
 * A hang cannot.
 * <p>
 * <strong>Only in the real daemon.</strong> Started from {@code main}, never from the object that
 * builds the server, so nothing in a test reaches the network by existing.
 * <p>
 * Offline projects are never contacted: that is enforced in {@link UpstreamDistance}, where the
 * security class is, rather than here.
 */
public final class UpstreamWatch implements AutoCloseable {

    /** How often to look, when nothing says otherwise. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(15);

    /** Minutes between passes. {@code 0} turns the measuring off entirely. */
    public static final String INTERVAL_VARIABLE = "SOKAR_UPSTREAM_MINUTES";

    private final SokarContext context;

    private final Duration interval;

    private volatile boolean running = true;

    /**
     * Constructor.
     *
     * @param context Where the paths and the projects come from.
     * @param interval How often to look. Zero or negative means never.
     */
    public UpstreamWatch(SokarContext context, Duration interval) {
        this.context = context;
        this.interval = interval;
    }

    /**
     * Returns how often to look on this machine.
     * <p>
     * Overridable and switchable off, because a daemon that reaches the network on its own is
     * something an operator is entitled to refuse - on a metered connection, or on a machine that
     * is meant to be quiet.
     *
     * @param environment Reads an environment variable.
     * @return The interval, or {@link Duration#ZERO} when it is turned off.
     */
    public static Duration interval(java.util.function.UnaryOperator<String> environment) {
        final String said = environment.apply(INTERVAL_VARIABLE);
        if (said == null || said.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        try {
            return Duration.ofMinutes(Long.parseLong(said.strip()));
        } catch (NumberFormatException ex) {
            // Not a reason to refuse to start a daemon. A misspelt variable falls back to the
            // default rather than turning a feature off silently, which is the failure that would
            // be discovered weeks later as "the number never updates".
            return DEFAULT_INTERVAL;
        }
    }

    /**
     * Measures every project once and records what it found.
     * <p>
     * Never throws: one upstream that is down, renamed or behind a credential nobody has must not
     * stop the others being measured.
     *
     * @return How many projects were looked at.
     */
    public int measureOnce() {

        final UpstreamRecords records = new UpstreamRecords(context.paths().upstreamRecords());
        final List<ProjectInventory.Summary> projects = new ProjectInventory(context).projects();
        int looked = 0;

        for (final ProjectInventory.Summary summary : projects) {
            if (summary.file() == null || summary.mirror() == null) {
                // Nothing to read a security class from, or no mirror to compare. Left as it was
                // rather than recorded as a failure: neither is something anybody has to fix.
                continue;
            }
            try {
                final Project project = GateSupport.project(Path.of(summary.file()));
                records.put(summary.name(), UpstreamDistance.measure(new ProcessCommandRunner(),
                        Path.of(summary.mirror()), GateMode.of(project.securityClass())));
                looked++;
            } catch (RuntimeException ex) {
                // An unreadable project file, or a record that cannot be written. The next pass
                // tries again, and the listing keeps whatever it had.
                continue;
            }
        }
        return looked;
    }

    /**
     * Starts looking, on a thread of its own.
     * <p>
     * The first pass waits one interval rather than running at start-up: a daemon that reached the
     * network the moment it came up would do so on every login, and nothing is waiting for the
     * answer that early.
     */
    public void start() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        Thread.ofVirtual().name("sokar-upstream").start(() -> {
            while (running) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running) {
                    measureOnce();
                }
            }
        });
    }

    @Override
    public void close() {
        running = false;
    }
}
