package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link VaultServeCommand}, covering which endpoint it is asked to serve.
 */
class VaultServeCommandTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = new CommandLine(new SokarCli(), new SokarFactory(context));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void refusesBothEndpointsAtOnce(@TempDir Path dir) {

        // One of them is where the agent will look. Serving the other fails as an authentication
        // error, which is the hardest possible symptom to trace back to this.
        final int code = execute(context(dir), "vault", "serve",
                "--socket", dir.resolve("v.sock").toString(), "--listen", "9419",
                "--agent", "uc", "--upstream", "https://api.example.com");

        assertThat(code).isEqualTo(64);
        assertThat(err.toString()).contains("exactly one of --socket and --listen");
    }

    @Test
    void refusesNeitherEndpoint(@TempDir Path dir) {

        final int code = execute(context(dir), "vault", "serve",
                "--agent", "uc", "--upstream", "https://api.example.com");

        assertThat(code).isEqualTo(64);
        assertThat(err.toString()).contains("exactly one of --socket and --listen");
    }
}
