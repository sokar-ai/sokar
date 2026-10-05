package org.fuin.sokar.clearance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Backlog}.
 */
class BacklogTest {

    @Test
    void anEventThatFitsArrivesAsItCame() throws InterruptedException {
        final Backlog backlog = new Backlog(2);
        backlog.offer(Map.of("destination", "pypi.org"));

        assertThat(backlog.poll(0, TimeUnit.MILLISECONDS)).isEqualTo(Map.of("destination", "pypi.org"));
        assertThat(backlog.poll(0, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void whatDidNotFitIsToldOnTheNextEventTaken() throws InterruptedException {
        // A question announced once and dropped is never asked again: a subscriber not told
        // believes nothing is waiting while the task stays blocked.
        final Backlog backlog = new Backlog(2);
        for (int each = 0; each < 5; each++) {
            backlog.offer(Map.of("destination", "host" + each));
        }

        assertThat(backlog.poll(0, TimeUnit.MILLISECONDS))
                .containsEntry("destination", "host0").containsEntry(Backlog.MISSED, 3L);
        assertThat(backlog.poll(0, TimeUnit.MILLISECONDS))
                .as("told once, not again")
                .doesNotContainKey(Backlog.MISSED);
    }

    @Test
    void aCountFromAnEarlierBacklogIsAddedTo() throws InterruptedException {
        // The daemon reads each task's watcher through a backlog of its own: a client reading
        // through both learns about the drops in either.
        final Backlog backlog = new Backlog(1);
        backlog.offer(Map.of("destination", "pypi.org", Backlog.MISSED, 4L));
        backlog.offer(Map.of("destination", "dropped"));

        assertThat(backlog.poll(0, TimeUnit.MILLISECONDS)).containsEntry(Backlog.MISSED, 5L);
    }

    @Test
    void aCountFromAnEarlierBacklogPassesWhenNothingWasDroppedHere() throws InterruptedException {
        final Backlog backlog = new Backlog(1);
        backlog.offer(Map.of("destination", "pypi.org", Backlog.MISSED, 4L));

        assertThat(backlog.poll(0, TimeUnit.MILLISECONDS)).containsEntry(Backlog.MISSED, 4L);
    }
}
