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

        final ProjectRegistry registry = new ProjectRegistry(dir.resolve("deep/projects"));
        registry.remember("uc", dir.resolve("uc/project.yml"));

        assertThat(registry.all()).containsEntry("uc", dir.resolve("uc/project.yml").toString());
    }

    @Test
    void keepsEveryProjectItHasBeenTold(@TempDir Path dir) {

        final ProjectRegistry registry = new ProjectRegistry(dir.resolve("projects"));
        registry.remember("one", dir.resolve("one/project.yml"));
        registry.remember("two", dir.resolve("two/project.yml"));

        assertThat(registry.all()).hasSize(2);
    }

    @Test
    void takesTheNewestAnswerForAProjectThatMoved(@TempDir Path dir) {

        // A checkout somewhere else is the same project, and the last run is the one that says
        // where it is now.
        final ProjectRegistry registry = new ProjectRegistry(dir.resolve("projects"));
        registry.remember("uc", dir.resolve("old/project.yml"));
        registry.remember("uc", dir.resolve("new/project.yml"));

        assertThat(registry.all()).containsEntry("uc", dir.resolve("new/project.yml").toString());
    }

    @Test
    void readsNothingBeforeAnyTaskHasRun(@TempDir Path dir) {

        assertThat(new ProjectRegistry(dir.resolve("projects")).all()).isEmpty();
    }

    @Test
    void survivesAnEntrySomebodyEmptied(@TempDir Path dir) throws IOException {

        // One unreadable entry costs one path, not the whole listing - and the path comes back on
        // the next task start, which is not worth failing a run over.
        final Path registry = dir.resolve("projects");
        Files.createDirectories(registry);
        Files.writeString(registry.resolve("broken"), "", StandardCharsets.UTF_8);
        new ProjectRegistry(registry).remember("uc", dir.resolve("uc/project.yml"));

        assertThat(new ProjectRegistry(registry).all()).containsOnlyKeys("uc");
    }

    @Test
    void twoStartsAtOnceCannotCorruptEachOther(@TempDir Path dir) throws Exception {

        // The defect this shape exists to remove: one document meant read-modify-write, and the
        // first version wrote through a shared temporary file, so two writers could interleave
        // into the bytes that were then moved into place.
        final Path registry = dir.resolve("projects");
        final int writers = 24;
        final java.util.concurrent.CountDownLatch go =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final String name = "project-" + i;
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
                new ProjectRegistry(registry).remember(name, dir.resolve(name + "/project.yml"));
            }));
        }
        go.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }

        // Every one of them, and each with its own path: nothing lost and nothing mixed.
        final java.util.Map<String, String> all = new ProjectRegistry(registry).all();
        assertThat(all).hasSize(writers);
        all.forEach((name, path) -> assertThat(path).endsWith(name + "/project.yml"));
    }

    @Test
    void theSameProjectStartedTwiceAtOnceIsStillOneEntry(@TempDir Path dir) throws Exception {

        final Path registry = dir.resolve("projects");
        final java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() ->
                    new ProjectRegistry(registry).remember("uc", dir.resolve("uc/project.yml"))));
        }
        for (final Thread thread : threads) {
            thread.join();
        }

        assertThat(new ProjectRegistry(registry).all())
                .containsExactly(java.util.Map.entry("uc",
                        dir.resolve("uc/project.yml").toString()));
    }

    @Test
    void leavesNoTemporaryFilesBehind(@TempDir Path dir) throws IOException {

        final Path registry = dir.resolve("projects");
        new ProjectRegistry(registry).remember("uc", dir.resolve("uc/project.yml"));

        try (var entries = Files.list(registry)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .containsExactly("uc");
        }
    }

    @Test
    void refusesANameThatWouldNotBeAFileName(@TempDir Path dir) throws IOException {

        // The second place that would have to be wrong for a name to escape the directory; the
        // first is where a project name is read.
        final Path registry = dir.resolve("projects");
        new ProjectRegistry(registry).remember("../escape", dir.resolve("project.yml"));

        assertThat(Files.exists(dir.resolve("escape"))).isFalse();
        assertThat(new ProjectRegistry(registry).all()).isEmpty();
    }
}
