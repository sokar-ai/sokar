package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.fuin.sokar.gate.UpstreamDistance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link UpstreamRecords}.
 * <p>
 * Written from a timer while every project listing reads it, so what matters is that a reader
 * never sees half a record and never sees a number it should not trust.
 */
class UpstreamRecordsTest {

    @Test
    void remembersANumberAndWhenItWasTaken(@TempDir Path dir) {

        final Instant when = Instant.parse("2026-09-07T17:44:30Z");
        final UpstreamRecords records = new UpstreamRecords(dir);
        records.put("uc", new UpstreamDistance.Distance(3, when,
                UpstreamDistance.Reason.MEASURED, null));

        final UpstreamDistance.Distance back = records.get("uc");
        assertThat(back.behind()).isEqualTo(3);
        assertThat(back.measured()).isEqualTo(when);
        assertThat(back.reason()).isEqualTo(UpstreamDistance.Reason.MEASURED);
    }

    @Test
    void aProjectNothingHasLookedAtSaysSoRatherThanZero(@TempDir Path dir) {

        // Zero and never-checked are the same number and different sentences. An interface that
        // read the number alone would say "up to date" about something nobody has ever measured.
        final UpstreamDistance.Distance back = new UpstreamRecords(dir).get("never-seen");

        assertThat(back.reason()).isEqualTo(UpstreamDistance.Reason.NEVER_CHECKED);
        assertThat(back.measured()).isNull();
    }

    @Test
    void keepsTheReasonAndTheDetailOfAFailure(@TempDir Path dir) {

        final UpstreamRecords records = new UpstreamRecords(dir);
        records.put("uc", new UpstreamDistance.Distance(0, null, UpstreamDistance.Reason.FAILED,
                "could not read from remote repository"));

        final UpstreamDistance.Distance back = records.get("uc");
        assertThat(back.reason()).isEqualTo(UpstreamDistance.Reason.FAILED);
        assertThat(back.detail()).isEqualTo("could not read from remote repository");
        assertThat(back.measured()).isNull();
    }

    @Test
    void aDetailWithATabInItDoesNotSplitTheRecord(@TempDir Path dir) {

        // git's own output is what goes in here, and it contains whatever it contains. A record
        // that could be split by its own content would come back as a different reason entirely.
        final UpstreamRecords records = new UpstreamRecords(dir);
        records.put("uc", new UpstreamDistance.Distance(0, null, UpstreamDistance.Reason.FAILED,
                "fatal:\tcould not read\tfrom remote"));

        assertThat(new UpstreamRecords(dir).get("uc").reason())
                .isEqualTo(UpstreamDistance.Reason.FAILED);
        assertThat(new UpstreamRecords(dir).get("uc").detail())
                .isEqualTo("fatal: could not read from remote");
    }

    @Test
    void anUnreadableRecordReadsAsNeverCheckedRatherThanThrowing(@TempDir Path dir)
            throws Exception {

        // The one answer that is true whatever the file said. A listing must not fail because a
        // record was truncated, hand-edited, or written by a newer build.
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("uc"), "WHAT\tIS\tTHIS\n", StandardCharsets.UTF_8);

        assertThat(new UpstreamRecords(dir).get("uc").reason())
                .isEqualTo(UpstreamDistance.Reason.NEVER_CHECKED);
    }

    @Test
    void oneProjectPerFileSoConcurrentWritersCannotLoseEachOther(@TempDir Path dir)
            throws Exception {

        // The shape the project registry had to be changed to, measured there: a single shared
        // document lost entries under concurrent writers. This is written from a timer while
        // listings read it, so it is the same race and gets the same answer.
        final UpstreamRecords records = new UpstreamRecords(dir);
        final int writers = 24;
        final java.util.concurrent.CountDownLatch start =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final int index = i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                records.put("project-" + index, new UpstreamDistance.Distance(index,
                        Instant.now(), UpstreamDistance.Reason.MEASURED, null));
            }));
        }
        start.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }

        for (int i = 0; i < writers; i++) {
            assertThat(records.get("project-" + i).behind())
                    .as("project-%d", i).isEqualTo(i);
        }
    }
}
