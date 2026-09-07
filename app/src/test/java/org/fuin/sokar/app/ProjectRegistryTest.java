package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProjectRegistry}, the only record of where a project's file is.
 */
class ProjectRegistryTest {

    @Test
    void remembersWhereAProjectFileIs(@TempDir Path dir) {

        final ProjectRegistry registry = new ProjectRegistry(dir.resolve("deep/projects.json"));
        registry.remember("uc", dir.resolve("uc/project.yml"));

        assertThat(registry.all()).containsEntry("uc", dir.resolve("uc/project.yml").toString());
    }

    @Test
    void keepsEveryProjectItHasBeenTold(@TempDir Path dir) {

        final ProjectRegistry registry = new ProjectRegistry(dir.resolve("projects.json"));
        registry.remember("one", dir.resolve("one/project.yml"));
        registry.remember("two", dir.resolve("two/project.yml"));

        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void takesTheNewestAnswerForAProjectThatMoved(@TempDir Path dir) {

        // A checkout somewhere else is the same project, and the last run is the one that says
        // where it is now.
        final ProjectRegistry registry = new ProjectRegistry(dir.resolve("projects.json"));
        registry.remember("uc", dir.resolve("old/project.yml"));
        registry.remember("uc", dir.resolve("new/project.yml"));

        assertThat(registry.all()).containsEntry("uc", dir.resolve("new/project.yml").toString());
    }

    @Test
    void readsNothingBeforeAnyTaskHasRun(@TempDir Path dir) {

        assertThat(new ProjectRegistry(dir.resolve("projects.json")).all()).isEmpty();
    }

    @Test
    void survivesAFileSomebodyEdited(@TempDir Path dir) throws IOException {

        // Losing this costs a path that comes back on the next task start, which is not worth
        // failing a run over.
        final Path file = dir.resolve("projects.json");
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        assertThat(new ProjectRegistry(file).all()).isEmpty();
        new ProjectRegistry(file).remember("uc", dir.resolve("uc/project.yml"));
        assertThat(new ProjectRegistry(file).all()).containsKey("uc");
    }

    @Test
    void leavesNoHalfWrittenDocumentBehind(@TempDir Path dir) throws IOException {

        // Two tasks can start at the same moment and both write this.
        final Path file = dir.resolve("projects.json");
        new ProjectRegistry(file).remember("uc", dir.resolve("uc/project.yml"));

        assertThat(Files.list(dir).map(path -> path.getFileName().toString()).toList())
                .containsExactly("projects.json");
    }
}
