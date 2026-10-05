package org.fuin.sokar.app;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Follows every project this account was told to follow, on a schedule.
 * <p>
 * <strong>The machine pulls; nothing pushes to it.</strong> The same shape as the two watches beside
 * it, and for the same reason: the daemon binds no network interface, so configuration arrives
 * because this goes and looks.
 * <p>
 * <strong>One project's trouble is not another's.</strong> A repository that cannot be reached, a
 * commit nobody signed, a file that does not read as a project - each stops that project and no
 * other, and the next tick tries again.
 * <p>
 * <strong>It changes what a project says and nothing else.</strong> Not the vault, not what a person
 * held, not a running task. A commit cannot start work, stop work or release a message somebody
 * stopped - and that boundary is what keeps this a way of configuring a machine rather than a way of
 * operating somebody else's.
 */
public final class ConfigurationWatch implements AutoCloseable {

    /** How often to look, when nothing says otherwise. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    /** Seconds between rounds. {@code 0} turns the following off entirely. */
    public static final String INTERVAL_VARIABLE = "SOKAR_FOLLOW_SECONDS";

    private final SokarContext context;

    private final Duration interval;

    private volatile boolean running = true;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param interval How often to look. Zero or negative means never.
     */
    public ConfigurationWatch(final SokarContext context, final Duration interval) {
        this.context = context;
        this.interval = interval;
    }

    /**
     * Returns how often to look on this machine.
     * <p>
     * Switchable off, because an account that follows nothing should not be fetching every five
     * minutes, and because an operator is entitled to refuse a background activity they did not ask
     * for.
     *
     * @param environment Reads an environment variable.
     * @return The interval, or {@link Duration#ZERO} when it is turned off.
     */
    public static Duration interval(final UnaryOperator<String> environment) {
        final String said = environment.apply(INTERVAL_VARIABLE);
        if (said == null || said.isBlank()) {
            return DEFAULT_INTERVAL;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(said.strip()));
        } catch (final NumberFormatException ex) {
            // A misspelt variable falls back rather than turning following off silently, which
            // would be found weeks later as "this machine never gets its configuration".
            return DEFAULT_INTERVAL;
        }
    }

    /**
     * Follows every project once.
     * <p>
     * Never throws: what one project cannot do must not stop the rest.
     *
     * @return What each project's attempt did, by name.
     */
    public Map<String, Reconcile.Result> once() {
        final Map<String, Reconcile.Result> done = new LinkedHashMap<>();
        final FollowedProjects projects = new FollowedProjects(context.paths().projects().followed());
        final java.util.List<FollowedProjects.Followed> all;
        try {
            all = projects.all();
        } catch (final IOException ex) {
            return done;
        }
        final Reconcile reconcile = new Reconcile(context);
        for (final FollowedProjects.Followed followed : all) {
            try {
                final Reconcile.Result result = reconcile.run(followed);
                // Only onto the record it read: a project unfollowed, or a first follow refused, while this fetched is
                // not brought back.
                if (projects.writeIfUnchanged(followed, Reconcile.after(followed, result))) {
                    done.put(followed.name(), result);
                }
            } catch (final IOException | RuntimeException ex) {
                continue;
            }
        }
        return done;
    }

    /**
     * Starts following, on a thread of its own.
     * <p>
     * The first round waits one interval: a daemon that came up and immediately fetched every
     * project would make starting it expensive, and nothing is waiting for configuration that early.
     */
    public void start() {
        if (interval.isZero() || interval.isNegative()) {
            return;
        }
        BackgroundPass.start("sokar-follow", () -> {
            while (running) {
                try {
                    Thread.sleep(interval);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (running) {
                    once();
                }
            }
        });
    }

    @Override
    public void close() {
        running = false;
    }
}
