package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link WorkspaceSetup#ownCheckout}: an empty mirror is seeded from where a command runs only when that
 * is the project's own repository.
 */
class SeedCheckoutTest {

    private static Path repository(Path dir, String projectFile) {
        new ProcessCommandRunner().runOrFail(Command.of("git", "init", "-q", dir.toString()));
        try {
            if (projectFile != null) {
                Files.writeString(dir.resolve("project.yml"), projectFile);
            }
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        return dir;
    }

    @Test
    void aCheckoutThatIsNotTheProjectsOwnSeedsNothing(@TempDir Path dir) throws Exception {

        // Through the daemon the directory is its home: a home kept in git gave the agent's mirror its whole history.
        final Path dotfiles = repository(Files.createDirectories(dir.resolve("home")), null);
        final Path other = repository(Files.createDirectories(dir.resolve("other")),
                "project:\n  name: \"someone-else\"\n  security_class: \"guarded\"\nimage:\n  base_image: \"ubuntu:24.04\"\n");
        final Path own = repository(Files.createDirectories(dir.resolve("own")), "project:\n  name: \"uc\"\n  security_class: \"guarded\"\nimage:\n  base_image: \"ubuntu:24.04\"\n");

        assertThat(WorkspaceSetup.ownCheckout("uc", dotfiles)).isNull();
        assertThat(WorkspaceSetup.ownCheckout("uc", other)).isNull();
        assertThat(WorkspaceSetup.ownCheckout("uc", own)).isEqualTo(own.toRealPath());
    }
}
