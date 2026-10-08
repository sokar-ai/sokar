package org.fuin.sokar.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ServingThreads}.
 */
class ServingThreadsTest {

    private static Thread ran(final String kind) throws InterruptedException {
        final Thread[] seen = new Thread[1];
        ServingThreads.start(kind, "sokar-test", () -> seen[0] = Thread.currentThread()).join(5_000);
        return seen[0];
    }

    @Test
    void platformDaemonThreadsUnlessVirtualIsAskedFor() throws InterruptedException {

        // One binary measured both ways, the default unchanged.
        assertThat(ran(null).isVirtual()).isFalse();
        assertThat(ran(null).isDaemon()).isTrue();
        assertThat(ran("platform").isVirtual()).isFalse();
        assertThat(ran("virtual").isVirtual()).isTrue();
        assertThat(ran("virtual").getName()).isEqualTo("sokar-test");
    }
}
