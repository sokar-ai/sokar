package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for telling an absent image from one built before the project changed under it.
 * <p>
 * The two that must never be reported as stale are the point: an image that does not say what it
 * was built from, and one whose project file has gone. Both would send somebody rebuilding for
 * minutes to fix something nothing knows to be wrong, and a rebuild that turns out to have been
 * unnecessary is how people learn to ignore the word.
 */
class ProjectReadinessTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    private Path projectFile(Path dir, String baseImage) throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  base_image: "%s"
                """.formatted(baseImage));
        return file;
    }

    /** What the image would record for a project built from this base image. */
    private String recipeOf(Path file) {
        return org.fuin.sokar.runtime.Containerfile.fingerprint(
                org.fuin.sokar.core.project.ProjectReader.read(file));
    }

    @Test
    void noImageIsAbsentRatherThanStale(@TempDir Path dir) throws Exception {

        final Path file = projectFile(dir, "ubuntu:24.04");

        assertThat(ProjectInventory.readiness(context(dir), "demo", file, false))
                .isEqualTo(ProjectInventory.Readiness.ABSENT);
    }

    @Test
    void anImageBuiltFromThisFileIsReady(@TempDir Path dir) throws Exception {

        final Path file = projectFile(dir, "ubuntu:24.04");
        runner.answering("inspect", recipeOf(file) + "\n");

        assertThat(ProjectInventory.readiness(context(dir), "demo", file, true))
                .isEqualTo(ProjectInventory.Readiness.READY);
    }

    @Test
    void anImageBuiltBeforeTheFileChangedIsStale(@TempDir Path dir) throws Exception {

        // The state that could not be answered at all before the image recorded its recipe: work
        // would start, run in something the file no longer describes, and nothing would say so.
        final Path file = projectFile(dir, "ubuntu:24.04");
        final String beforeTheEdit = recipeOf(file);
        Files.writeString(file, Files.readString(file).replace("ubuntu:24.04", "debian:12"));
        runner.answering("inspect", beforeTheEdit + "\n");

        assertThat(ProjectInventory.readiness(context(dir), "demo", file, true))
                .isEqualTo(ProjectInventory.Readiness.STALE);
    }

    @Test
    void anImageThatDoesNotSayWhatItWasBuiltFromIsUnknown(@TempDir Path dir) throws Exception {

        // Built by a Sokar that wrote no recipe label. Nothing knows whether it is current, and
        // guessing stale would cost somebody a rebuild to fix nothing.
        final Path file = projectFile(dir, "ubuntu:24.04");
        runner.answering("inspect", "<no value>\n");

        assertThat(ProjectInventory.readiness(context(dir), "demo", file, true))
                .isEqualTo(ProjectInventory.Readiness.UNKNOWN);
    }

    @Test
    void anImageWhoseProjectFileHasGoneIsUnknown(@TempDir Path dir) throws Exception {

        // Nothing left to compare against. Reporting stale would be asserting something no file
        // says.
        assertThat(ProjectInventory.readiness(context(dir), "demo", null, true))
                .isEqualTo(ProjectInventory.Readiness.UNKNOWN);
    }

    @Test
    void anUnreadableProjectFileCannotDeclareAnImageOutOfDate(@TempDir Path dir) throws Exception {

        final Path file = dir.resolve("project.yml");
        Files.writeString(file, "this is not: [valid: yaml\n");
        runner.answering("inspect", "0123456789abcdef\n");

        assertThat(ProjectInventory.readiness(context(dir), "demo", file, true))
                .isEqualTo(ProjectInventory.Readiness.UNKNOWN);
    }

    @Test
    void onlyWhatDecidesTheImageChangesTheRecipe(@TempDir Path dir) throws Exception {

        // Egress, limits and the upstream change what a task may do, not what it is built from.
        // Counting them would call an image stale after an edit that could not have changed it.
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                egress:
                  sets: ["maven"]
                """);
        final String before = recipeOf(file);
        Files.writeString(file, Files.readString(file).replace("[\"maven\"]", "[\"nodejs\"]"));

        assertThat(recipeOf(file)).isEqualTo(before);
    }

    @Test
    void changingTheImageSnippetChangesTheRecipe(@TempDir Path dir) throws Exception {

        // The snippet is lines a project adds to its own image, so an edit to it changes what was
        // built as surely as a new base image does. Leaving it out would report an image as
        // current after exactly the edit somebody made to change it.
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, """
                project:
                  name: "demo"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                  snippet: |
                    RUN apt-get install -y jq
                """);
        final String before = recipeOf(file);
        Files.writeString(file, Files.readString(file).replace("jq", "ripgrep"));

        assertThat(recipeOf(file)).isNotEqualTo(before);
    }

    @Test
    void changingTheBaseImageChangesTheRecipe(@TempDir Path dir) throws Exception {

        final Path file = projectFile(dir, "ubuntu:24.04");
        final String before = recipeOf(file);
        Files.writeString(file, Files.readString(file).replace("ubuntu:24.04", "debian:12"));

        assertThat(recipeOf(file)).isNotEqualTo(before);
    }

    @Test
    void theRecipeIsShortEnoughToShowAndLongEnoughToTellApart(@TempDir Path dir) throws Exception {

        assertThat(recipeOf(projectFile(dir, "ubuntu:24.04")))
                .hasSize(16).matches("[0-9a-f]+");
    }
}
