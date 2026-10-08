package org.fuin.sokar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link HelperPid#stop}: a helper is stopped with what it started.
 */
class HelperPidStopTest {

    @Test
    void aHelperIsStoppedWithTheProcessesItStartedSoNoneOutlivesItsTask(@TempDir final Path dir) throws Exception {

        // A task's build watch was stopped through its pid file and its build reader, a process
        // it had started, kept running for hours after the task was removed.
        final Process helper = new ProcessBuilder("sh", "-c", "sleep 300 & wait").start();
        final Path pidFile = dir.resolve("builds.pid");
        HelperPid.record(pidFile, helper.toHandle());
        ProcessHandle child = null;
        for (int i = 0; i < 50 && child == null; i++) {
            final List<ProcessHandle> started = helper.toHandle().children().toList();
            child = started.isEmpty() ? null : started.getFirst();
            Thread.sleep(50);
        }
        assertThat(child).as("the helper's own process").isNotNull();

        HelperPid.stop(HelperPid.verified(pidFile).orElseThrow());

        assertThat(helper.waitFor(5, TimeUnit.SECONDS)).isTrue();
        final ProcessHandle reader = child;
        assertThat(reader.onExit().get(5, TimeUnit.SECONDS).isAlive()).as("what the helper started").isFalse();
    }
}
