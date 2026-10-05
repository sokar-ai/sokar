package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.runtime.Podman;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for preparing an environment without starting a task.
 * <p>
 * The depths are the feature, so what is asserted is what actually reaches podman. A depth that
 * was accepted, reported and then not applied would be the worst version of this: somebody waits
 * for a full rebuild, gets a cached one, and the thing they were trying to fix is still there.
 */
class PreparationTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    private Path projectFile(Path dir) throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """);
        return file;
    }

    private Preparation.Result prepare(Path dir, Podman.Rebuild rebuild, boolean dryRun)
            throws Exception {
        return Preparation.prepare(context(dir), projectFile(dir), null, rebuild, dryRun,
                new PrintWriter(out, true));
    }

    /** The arguments of the podman build that actually ran. */
    private List<String> build() {
        return runner.invocations().stream()
                .map(command -> command.arguments())
                .filter(arguments -> arguments.contains("build"))
                .findFirst().orElse(List.of());
    }

    @Test
    void aCachedRebuildPassesNeitherFlag(@TempDir Path dir) throws Exception {

        // Not "skip the build": the build runs and podman's cache decides layer by layer, which
        // is what every task start already does.
        assertThat(prepare(dir, Podman.Rebuild.CACHED, false).outcome())
                .isEqualTo(Preparation.Outcome.PREPARED);

        assertThat(build()).contains("build").doesNotContain("--no-cache");
        assertThat(String.join(" ", build())).doesNotContain("SOKAR_LAYER_EPOCH");
    }

    @Test
    void rebuildingTheAgentInvalidatesFromTheSeamAndNotTheWholeImage(@TempDir Path dir)
            throws Exception {

        // There is no podman flag for "rebuild from here". It works by changing a build argument
        // placed where the agent's layers begin - so if this stopped being passed, the middle
        // depth would silently become the cheapest one.
        prepare(dir, Podman.Rebuild.AGENT, false);

        assertThat(String.join(" ", build()))
                .contains("--build-arg").contains("SOKAR_LAYER_EPOCH=");
        assertThat(build()).as("keeping the packages is the whole point of this depth")
                .doesNotContain("--no-cache");
    }

    @Test
    void discardingEverythingSaysSoToPodman(@TempDir Path dir) throws Exception {

        prepare(dir, Podman.Rebuild.EVERYTHING, false);

        assertThat(build()).contains("--no-cache");
    }

    @Test
    void aPreviewBuildsNothingAtAll(@TempDir Path dir) throws Exception {

        // Somebody checking what a rebuild would cost must not pay it by asking.
        assertThat(prepare(dir, Podman.Rebuild.EVERYTHING, true).outcome())
                .isEqualTo(Preparation.Outcome.PREVIEWED);

        assertThat(build()).isEmpty();
    }

    @Test
    void aProjectFileThatCannotBeReadIsNamedRatherThanBuiltAround(@TempDir Path dir) {

        final Preparation.Result result = Preparation.prepare(context(dir),
                dir.resolve("nothing-here.yml"), null, Podman.Rebuild.CACHED, false,
                new PrintWriter(out, true));

        assertThat(result.outcome()).isEqualTo(Preparation.Outcome.NO_SUCH_PROJECT);
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void everyDepthSaysWhatItKeeps() {

        // A depth whose cost is not stated is the button people press once and then avoid.
        for (final Podman.Rebuild rebuild : Podman.Rebuild.values()) {
            assertThat(Preparation.describe(rebuild)).isNotBlank();
        }
        assertThat(Preparation.describe(Podman.Rebuild.AGENT))
                .contains("keep the base image");
    }

    @Test
    void progressIsReportedBeforeTheBuildRatherThanAfterIt(@TempDir Path dir) throws Exception {

        // A build takes minutes, and an interface showing nothing for that long is
        // indistinguishable from one that hung.
        prepare(dir, Podman.Rebuild.CACHED, false);

        assertThat(out.toString()).contains("building").contains("rebuild");
    }
}
