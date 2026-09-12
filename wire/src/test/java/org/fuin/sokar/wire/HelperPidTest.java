package org.fuin.sokar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whether a recorded process id still names the process it was written for.
 */
class HelperPidTest {

    @Test
    void thisProcessRecognisesItself(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("helper.pid");
        HelperPid.record(file);

        assertThat(HelperPid.verified(file)).get()
                .extracting(ProcessHandle::pid)
                .isEqualTo(ProcessHandle.current().pid());
    }

    @Test
    void anIdThatNowBelongsToSomethingElseIsNotSignalled(@TempDir Path dir) throws Exception {

        // The defect this exists for: process ids are reused. A helper that exited leaves its
        // file behind, and by the time something cleans up, that number can belong to an editor,
        // an ssh agent or another task's helper - all owned by the same user, so the operating
        // system permits the signal. Recording the start time is what tells them apart.
        final Path file = dir.resolve("helper.pid");
        Files.writeString(file, ProcessHandle.current().pid() + " 1",
                StandardCharsets.UTF_8);

        assertThat(HelperPid.verified(file))
                .as("same id, a start time from 1970: not the same process").isEmpty();
    }

    @Test
    void aRecordWithoutAStartTimeIsNeverSignalled(@TempDir Path dir) throws Exception {

        // What an older build wrote: a bare number. There is nothing to compare, and the safe
        // answer to "is this still the helper" is no - an orphaned helper is visible and can be
        // stopped by name, whereas a process killed by mistake is simply gone.
        final Path file = dir.resolve("helper.pid");
        Files.writeString(file, String.valueOf(ProcessHandle.current().pid()),
                StandardCharsets.UTF_8);

        assertThat(HelperPid.verified(file)).isEmpty();
    }

    @Test
    void rubbishIsNotSignalledEither(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("helper.pid");
        Files.writeString(file, "not a pid at all", StandardCharsets.UTF_8);
        assertThat(HelperPid.verified(file)).isEmpty();

        assertThat(HelperPid.verified(dir.resolve("absent.pid"))).isEmpty();
    }

    @Test
    void theRecordIsOwnerOnly(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("helper.pid");
        HelperPid.record(file);

        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }
}
