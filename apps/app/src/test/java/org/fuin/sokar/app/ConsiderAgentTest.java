package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.fuin.sokar.agent.api.InstalledAgents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TaskLaunch#considerAgent}: a refusal says what is wrong with the agent asked for.
 */
class ConsiderAgentTest {

    @Test
    void anAgentThatIsInstalledButDidNotStartIsSaidAsSuchNeverAsNoneFound(@TempDir final Path dir) throws IOException {

        // "No agent named 'claude' is installed. Found: none" while it was installed: its adapter had not started,
        // and the reason was left out.
        final Path agents = Files.createDirectories(dir.resolve("agents"));
        final Path adapter = agents.resolve(AgentDirectory.PREFIX + "claude");
        Files.writeString(adapter, "#!/bin/sh\necho 'cannot bind its socket' >&2\nexit 3\n");
        Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));

        final AgentChoice.Choice choice = TaskLaunch.considerAgent(
                new InstalledAgents(new AgentDirectory(List.of(agents)), dir.resolve("run")), "claude");

        assertThat(choice.detail()).contains("'claude' is installed but did not start")
                .doesNotContain("Found: none");
    }
}
