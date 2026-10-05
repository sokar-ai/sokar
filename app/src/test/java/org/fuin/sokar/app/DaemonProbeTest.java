package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the daemon's line in {@code sokar doctor}: a daemon older than this command is said, with what to run.
 */
class DaemonProbeTest {

    @Test
    void aDaemonStillOnTheVersionBeforeAnUpdateIsSaidWithTheTwoCommandsThatFixIt(@TempDir Path dir) throws Exception {

        // An update replaced the binaries and started nothing: the daemon went on as the old version, and nothing
        // said so.
        final Path socket = Files.createFile(dir.resolve("sokard.sock"));

        final Probe probe = DoctorCommand.daemon(socket, "0.4.0~snapshot.240", path -> "0.4.0~snapshot.233");

        assertThat(probe.state()).isEqualTo(Probe.State.DEGRADED);
        assertThat(probe.detail()).contains("0.4.0~snapshot.233").contains("0.4.0~snapshot.240");
        assertThat(probe.action()).contains("systemctl --user daemon-reload").contains("systemctl --user restart sokard");
    }

    @Test
    void theSameVersionIsFineAndNoDaemonIsSaidAsSuch(@TempDir Path dir) throws Exception {
        final Path socket = Files.createFile(dir.resolve("sokard.sock"));

        assertThat(DoctorCommand.daemon(socket, "1", path -> "1").state()).isEqualTo(Probe.State.OK);
        final Probe none = DoctorCommand.daemon(dir.resolve("absent.sock"), "1", path -> "1");
        assertThat(none.state()).isEqualTo(Probe.State.DEGRADED);
        assertThat(none.action()).contains("systemctl --user start sokard");
        final Probe silent = DoctorCommand.daemon(socket, "1", path -> {
            throw new IllegalStateException("no answer");
        });
        assertThat(silent.state()).isEqualTo(Probe.State.UNKNOWN);
    }
}
