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

    private final java.util.function.BooleanSupplier vaultOpen;

    private volatile boolean running = true;

    /** How often a round's wait looks whether the vault opened for a project that waits for it. */
    static final Duration VAULT_LOOK = Duration.ofSeconds(2);

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param interval How often to look. Zero or negative means never.
     */
    public ConfigurationWatch(final SokarContext context, final Duration interval) {
        this(context, interval, () -> context.vault().exists() && context.opener().isPresent());
    }

    /**
     * Constructor, saying itself whether the vault is open.
     *
     * @param context The machine.
     * @param interval How often to look.
     * @param vaultOpen Whether this account's vault can be read now without asking anybody.
     */
    ConfigurationWatch(final SokarContext context, final Duration interval,
            final java.util.function.BooleanSupplier vaultOpen) {
        this.context = context;
        this.interval = interval;
        this.vaultOpen = vaultOpen;
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
        return once(followed -> true);
    }

    /**
     * Follows again, now, every project that could not be fetched because the vault was shut - once it is open.
     * <p>
     * Opening the vault is when what waited for it can go on: left to the next round, a project
     * said {@code vault_locked} for up to five minutes after the vault had been opened. Asked every few seconds, so
     * it reads only the list of projects until one of them waits, and the vault only then.
     *
     * @return What each project's attempt did, by name; empty when nothing waited or the vault is still shut.
     */
    public Map<String, Reconcile.Result> afterTheVault() {
        final java.util.function.Predicate<FollowedProjects.Followed> waited =
                followed -> Reconcile.Outcome.VAULT_LOCKED.name().equals(followed.outcome());
        try {
            if (new FollowedProjects(context.paths().projects().followed()).all().stream().noneMatch(waited)
                    || !vaultOpen.getAsBoolean()) {
                return Map.of();
            }
        } catch (final IOException | RuntimeException ex) {
            return Map.of();
        }
        return once(waited);
    }

    /**
     * Follows one project now, or every one, at a person's word rather than at the next round.
     *
     * @param name The project, or {@code null} for every project this account follows.
     * @return What each project's attempt did, by name; {@code null} when a named project is not followed here.
     */
    public java.util.@org.jspecify.annotations.Nullable Map<String, Reconcile.Result> refresh(
            final @org.jspecify.annotations.Nullable String name) {
        if (name != null) {
            try {
                if (new FollowedProjects(context.paths().projects().followed()).all().stream()
                        .noneMatch(followed -> followed.name().equals(name))) {
                    return null;
                }
            } catch (final IOException ex) {
                return null;
            }
        }
        return once(followed -> name == null || followed.name().equals(name));
    }

    /**
     * Returns whether a project's attempt brought it to what its repository holds.
     *
     * @param result What the attempt did.
     * @return {@code true} when it applied a commit or held it already.
     */
    public static boolean fetched(final Reconcile.Result result) {
        return result.outcome() == Reconcile.Outcome.APPLIED || result.outcome() == Reconcile.Outcome.UNCHANGED;
    }

    private Map<String, Reconcile.Result> once(final java.util.function.Predicate<FollowedProjects.Followed> which) {
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
            if (!which.test(followed)) {
                continue;
            }
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
                // The round's wait in short steps, each looking whether the vault opened for what waits for it.
                final long round = System.nanoTime() + interval.toNanos();
                while (running && System.nanoTime() < round) {
                    try {
                        Thread.sleep(Math.min(VAULT_LOOK.toMillis(),
                                Math.max(1, (round - System.nanoTime()) / 1_000_000)));
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (running) {
                        afterTheVault();
                    }
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
