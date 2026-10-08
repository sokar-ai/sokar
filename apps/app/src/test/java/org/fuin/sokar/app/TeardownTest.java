package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Teardown}.
 */
class TeardownTest {

    @Test
    void cleansUpOnTheNormalPath() throws Exception {

        final AtomicInteger runs = new AtomicInteger();
        try (Teardown teardown = Teardown.arm(runs::incrementAndGet)) {
            assertThat(runs).hasValue(0);
        }
        assertThat(runs).hasValue(1);
    }

    @Test
    void cleansUpOnceEvenWhenAskedTwice() throws Exception {

        // Both the shutdown hook and the finally fire on a signal. Removing a container twice is
        // harmless, but stopping a task twice writes a second record of what its workspace held.
        final AtomicInteger runs = new AtomicInteger();
        final Teardown teardown = Teardown.arm(runs::incrementAndGet);
        teardown.run();
        teardown.run();
        teardown.close();
        assertThat(runs).hasValue(1);
    }

    @Test
    void makesTheSecondCallerWaitForACleanupInProgress() throws Exception {

        // The point of the lock. The runtime waits for shutdown hook threads and not for the
        // main thread, so a hook that saw "somebody else has it" and returned would let the
        // process exit with the container half stopped.
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger finished = new AtomicInteger();

        final Teardown teardown = Teardown.arm(() -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            finished.incrementAndGet();
        });

        final Thread first = new Thread(teardown::run);
        first.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        final AtomicInteger sawFinished = new AtomicInteger(-1);
        final Thread second = new Thread(() -> {
            teardown.run();
            sawFinished.set(finished.get());
        });
        second.start();

        // The second caller is blocked, not done: nothing has finished yet.
        assertThat(finished).hasValue(0);
        release.countDown();
        first.join(5000);
        second.join(5000);

        assertThat(finished).hasValue(1);
        assertThat(sawFinished).hasValue(1);
        teardown.close();
    }
}
