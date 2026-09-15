package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the project wizard a start runs when there is no project file yet.
 */
class TaskLaunchWizardTest {

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    @Test
    void leavesStandardInputOpenForTheSessionThatFollows(@TempDir Path dir) throws IOException {

        // Reported on the VM: a task started in a new project took no keystrokes - '/exit' did
        // nothing - and the terminal's answers to its queries appeared at the shell prompt after
        // leaving. The wizard's reader closed standard input, and the session attached after the
        // image was built was reading /dev/null.
        final boolean[] closed = { false };
        final InputStream in = new java.io.ByteArrayInputStream(
                "\noffline\n\n\n".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };
        final Path file = dir.resolve("project.yml");
        final TaskLaunch launch = new TaskLaunch(context(dir), new TaskLaunch.Request("shell", file,
                null, null, null, 8, null, false, false, "prompt", true));
        final StringWriter out = new StringWriter();

        final int code = launch.runWizard(in, new PrintWriter(out));

        assertThat(code).as(out.toString()).isZero();
        assertThat(file).exists();
        assertThat(closed[0]).as("standard input was closed").isFalse();
    }
}
