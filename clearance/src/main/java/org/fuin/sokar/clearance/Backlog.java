package org.fuin.sokar.clearance;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * The events waiting for one subscriber that reads more slowly than they arrive, and how many did
 * not fit.
 * <p>
 * Bounded, and an event that does not fit is dropped rather than waited for: the one offering it
 * is on the path of every blocked packet, and a subscriber that stopped reading must not hold that
 * up. <strong>A drop is counted, and the count goes out</strong> as {@link #MISSED} on the next
 * event taken: a question announced once and dropped here is never asked again, so a subscriber
 * that is not told believes nothing is waiting while a task stays blocked. A drop happens only
 * when the backlog is full, so an event always follows to carry the count.
 * <p>
 * An event that already carries a count - one that passed a backlog before this one - has this
 * backlog's added to it, so a subscriber reading through two of them learns about both.
 */
public final class Backlog {

    /** The field an event carries the number of events dropped before it in. */
    public static final String MISSED = "missed";

    private final LinkedBlockingQueue<Map<String, Object>> queue;

    private final AtomicLong dropped = new AtomicLong();

    /**
     * Makes a backlog.
     *
     * @param capacity How many events may wait.
     */
    public Backlog(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Adds an event, or counts it as dropped when the backlog is full. Never waits.
     *
     * @param event The event.
     */
    public void offer(Map<String, Object> event) {
        if (!queue.offer(event)) {
            dropped.incrementAndGet();
        }
    }

    /**
     * Takes the next event, carrying the number dropped since the last one taken if any were.
     *
     * @param timeout How long to wait for one.
     * @param unit The unit of {@code timeout}.
     * @return The event, or {@code null} when none arrived in time.
     * @throws InterruptedException If interrupted while waiting.
     */
    public @Nullable Map<String, Object> poll(long timeout, TimeUnit unit) throws InterruptedException {
        final Map<String, Object> event = queue.poll(timeout, unit);
        if (event == null) {
            return null;
        }
        final long lost = dropped.getAndSet(0);
        if (lost == 0) {
            return event;
        }
        final Map<String, Object> told = new LinkedHashMap<>(event);
        told.put(MISSED, lost + (event.get(MISSED) instanceof Number earlier ? earlier.longValue() : 0));
        return told;
    }
}
