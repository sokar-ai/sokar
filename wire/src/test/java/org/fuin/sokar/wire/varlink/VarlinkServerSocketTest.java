package org.fuin.sokar.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What happens to the socket file, which is the part an interface trips over rather than the
 * protocol.
 */
class VarlinkServerSocketTest {

    @Test
    void aSocketNobodyIsListeningOnIsTakenOver(@TempDir Path dir) throws Exception {

        // The ordinary case after a machine was rebooted or a daemon was killed with SIGKILL:
        // a name with nothing behind it. Refusing to start over it would need somebody to delete
        // a file by hand before their daemon would run.
        final Path socket = dir.resolve("sokard.sock");
        try (VarlinkServer first = new VarlinkServer(socket, "org.example.Test1")) {
            assertThat(socket).exists();
        }
        assertThat(socket).as("close unlinks it").doesNotExist();

        Files.createFile(socket);
        try (VarlinkServer second = new VarlinkServer(socket, "org.example.Test1")) {
            assertThat(socket).exists();
        }
    }

    @Test
    void aSocketSomebodyIsListeningOnIsNotTakenAway(@TempDir Path dir) throws Exception {

        // The defect: binding unlinked whatever was there. The file is only a name, so unlinking
        // it does not stop the process holding the bound socket - it makes that process
        // unreachable. The first daemon went on running, serving nobody, and nothing said so.
        final Path socket = dir.resolve("sokard.sock");
        try (VarlinkServer first = new VarlinkServer(socket, "org.example.Test1")) {
            assertThatThrownBy(() -> new VarlinkServer(socket, "org.example.Test1"))
                    .isInstanceOf(VarlinkException.class)
                    .hasMessageContaining("Another server is already listening");
            assertThat(socket).as("the first one still has it").exists();
        }
    }

    @Test
    void closingUnlinksTheSocketSoNothingIsLeftAnswering(@TempDir Path dir) throws Exception {

        // An interface cannot tell a socket file with nothing behind it from a daemon that is
        // still starting: both refuse the connection. Leaving one behind is leaving a question
        // nobody can answer.
        final Path socket = dir.resolve("sokard.sock");
        final VarlinkServer server = new VarlinkServer(socket, "org.example.Test1");
        assertThat(socket).exists();
        server.close();
        assertThat(socket).doesNotExist();
    }
}
