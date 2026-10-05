package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link FollowCredential#sshCommand}: git runs it through a shell, so every path in it is one word.
 */
class FollowCredentialQuotingTest {

    @Test
    void aPathWithASpaceOrAShellCharacterStaysOneWord(@TempDir Path dir) {

        // Joined as it came, a state directory with a space broke every ssh fetch, and a key path with ';' ran as shell.
        final Path state = dir.resolve("my state");
        final XdgPaths xdg = XdgPaths.of(name -> "XDG_STATE_HOME".equals(name) ? state.toString() : null, dir);
        final SokarContext context = new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);

        final String command = FollowCredential.sshCommand(context, "/keys/id;touch x");

        assertThat(command).contains("UserKnownHostsFile='" + state.resolve("sokar"))
                .contains("-i '/keys/id;touch x'");
    }
}
