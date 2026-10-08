package org.fuin.sokar.wire.varlink;

import org.jspecify.annotations.Nullable;

/**
 * Starts the threads a daemon serves on and runs its long-lived passes on: platform threads, or virtual ones when
 * {@value #VARIABLE} is {@code virtual}.
 * <p>
 * Platform threads since a daemon answered no call for four minutes, the cause inferred - not measured - as virtual
 * threads blocked in native calls holding every carrier. The switch exists so one binary can be measured both ways;
 * the default stays until a measurement says otherwise.
 */
public final class ServingThreads {

    /** The environment variable that chooses the kind. */
    public static final String VARIABLE = "SOKAR_THREADS";

    private ServingThreads() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Starts a thread of the kind this process was told to use.
     *
     * @param name The thread's name, which a thread dump shows.
     * @param work What it does.
     * @return The thread.
     */
    public static Thread start(final String name, final Runnable work) {
        return start(System.getenv(VARIABLE), name, work);
    }

    /**
     * Starts a thread of the kind named.
     *
     * @param kind {@code virtual} for a virtual thread; anything else, or nothing, for a platform daemon thread.
     * @param name The thread's name.
     * @param work What it does.
     * @return The thread.
     */
    static Thread start(final @Nullable String kind, final String name, final Runnable work) {
        return "virtual".equals(kind) ? Thread.ofVirtual().name(name).start(work)
                : Thread.ofPlatform().daemon().name(name).start(work);
    }
}
