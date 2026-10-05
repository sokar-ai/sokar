package org.fuin.sokar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tests for {@link SocketContext}.
 * <p>
 * The behavior that matters is only observable on a machine running SELinux, so the checks that
 * need one say so rather than being asserted everywhere and passing for the wrong reason.
 */
class SocketContextTest {

    static boolean selinux() {
        return Files.isDirectory(Path.of("/sys/fs/selinux"));
    }

    @Test
    void namesTheTypeThePolicyDefines() {
        assertThat(SocketContext.TYPE).isEqualTo("sokar_socket_t");
    }

    @Test
    void doesNothingWhereThereIsNoSelinux() {

        // Binding must never fail because a machine has no policy, or no task would run there.
        try (SocketContext context = SocketContext.applied()) {
            assertThat(context.isApplied()).isEqualTo(selinux() && SocketContext.available());
        }
    }

    @Test
    void reportsNoPolicyWhereThereIsNoSelinux() {
        if (!selinux()) {
            assertThat(SocketContext.available()).isFalse();
            assertThat(SocketContext.selinuxPresent()).isFalse();
        }
    }

    @Test
    @EnabledIf("selinux")
    void keepsTheProcessSelinuxUserAndReplacesOnlyTheType() throws Exception {

        final String context = SocketContext.socketContext();

        assertThat(context).endsWith(":object_r:" + SocketContext.TYPE + ":s0");
        assertThat(context.split(":")[0])
                .isEqualTo(Files.readString(Path.of("/proc/thread-self/attr/current"))
                        .trim().replace("\0", "").split(":")[0]);
    }

    @Test
    @EnabledIf("selinux")
    void leavesNothingSetBehind() throws Exception {

        // An empty write is not a write at all, so a wrong clear would label every later socket.
        try (SocketContext ignored = SocketContext.applied()) {
            assertThat(ignored).isNotNull();
        }
        assertThat(Files.readString(Path.of("/proc/thread-self/attr/sockcreate"))
                .trim().replace("\0", "")).isEmpty();
    }
}
