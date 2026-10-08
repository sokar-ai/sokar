package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Test for {@link ProjectRefreshCommand}: a followed project re-checked now, at the person's word.
 */
class ProjectRefreshCommandTest {

    @TempDir
    Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private int run(final SokarContext context, final String... arguments) {
        final CommandLine line = SokarCli.commandLine(context);
        line.setOut(new PrintWriter(out, true));
        line.setErr(new PrintWriter(err, true));
        return line.execute(arguments);
    }

    @Test
    void fetchesTheNamedProjectNowSaysWhatItFoundAndFailsWhenTheFetchDid() throws Exception {

        // Nothing re-checked a follow at once; a person waited for the next round.
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(new ProcessCommandRunner(), new SokarPaths(xdg,
                dir.resolve("bin")), arguments -> 0);
        final FollowedProjects follows = new FollowedProjects(context.paths().projects().followed());
        follows.write(new FollowedProjects.Followed("p", dir.resolve("no-such.git").toString(), "", "", "APPLIED",
                ""));
        follows.write(new FollowedProjects.Followed("q", dir.resolve("no-such.git").toString(), "", "", "APPLIED",
                ""));

        assertThat(run(context, "project", "refresh", "p")).as("the fetch failed").isNotZero();
        assertThat(out.toString()).contains("p").doesNotContain("q ");
        assertThat(follows.all().stream().filter(each -> each.name().equals("p")).findFirst().orElseThrow()
                .outcome()).as("written down, as a round does").isNotEqualTo("APPLIED");
        assertThat(follows.all().stream().filter(each -> each.name().equals("q")).findFirst().orElseThrow()
                .outcome()).as("not asked").isEqualTo("APPLIED");

        assertThat(run(context, "project", "refresh", "nope")).isNotZero();
        assertThat(err.toString()).contains("nope").contains("follows no project");
    }
}
