package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a task of an offline project is never given an upstream on the command line: the host would clone its
 * mirror from it over the network.
 */
class OfflineUpstreamTest {

    @Test
    void anOfflineStartWithAnUpstreamIsRefusedBeforeAnythingConnects(@TempDir final Path dir) {
        final FakeCommandRunner runner = new FakeCommandRunner();
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        final Project offline = new Project("uc", "", SecurityClass.OFFLINE, "ubuntu:24.04", null);
        final StringWriter err = new StringWriter();

        final TaskWorkspace workspace = new WorkspaceSetup(context, "t", "https://example.org/uc.git")
                .openWorkspace(offline, true, new PrintWriter(new StringWriter()), new PrintWriter(err));

        assertThat(workspace).isNull();
        assertThat(err.toString()).contains("is offline, so it takes no --upstream").contains("sokar gate restore");
        assertThat(runner.invocations()).as("no git command ran").isEmpty();
    }
}
