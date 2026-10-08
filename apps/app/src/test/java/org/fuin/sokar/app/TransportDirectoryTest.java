package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link TransportDirectory}.
 */
class TransportDirectoryTest {

    @TempDir
    private Path temp;

    private Path adapter(final Path directory, final String fileName, final boolean executable)
            throws IOException {
        Files.createDirectories(directory);
        final Path file = directory.resolve(fileName);
        Files.writeString(file, "#!/bin/sh\n");
        Files.setPosixFilePermissions(file,
                PosixFilePermissions.fromString(executable ? "rwx------" : "rw-------"));
        return file;
    }

    @Test
    void finds_an_adapter_by_the_name_after_the_prefix() throws IOException {
        final Path packaged = temp.resolve("libexec");
        adapter(packaged, "sokar-message-transport-local", true);

        final TransportDirectory directory = new TransportDirectory(List.of(packaged));

        assertThat(directory.byName()).containsOnlyKeys("local");
        assertThat(directory.find("local")).isNotNull();
        assertThat(directory.find("git")).isNull();
    }

    @Test
    void ignores_what_is_not_an_adapter() throws IOException {
        final Path packaged = temp.resolve("libexec");
        adapter(packaged, "sokar-message-transport-local", true);
        adapter(packaged, "notes.txt", true);
        adapter(packaged, "sokar-agent-claude", true);
        adapter(packaged, "sokar-message-transport-half-installed", false);
        Files.createDirectory(packaged.resolve("sokar-message-transport-adirectory"));

        assertThat(new TransportDirectory(List.of(packaged)).byName()).containsOnlyKeys("local");
    }

    /**
     * An operator trying an adapter of their own must not have to remove the packaged one, and what
     * they put nearer has to win - otherwise "I replaced it" silently means "I did not".
     */
    @Test
    void an_adapter_of_ones_own_shadows_the_packaged_one() throws IOException {
        final Path mine = temp.resolve("data").resolve("transports");
        final Path packaged = temp.resolve("libexec");
        final Path ours = adapter(mine, "sokar-message-transport-local", true);
        adapter(packaged, "sokar-message-transport-local", true);

        final TransportDirectory directory = new TransportDirectory(List.of(mine, packaged));

        assertThat(directory.find("local")).isEqualTo(ours);
    }

    @Test
    void a_machine_with_no_transports_answers_empty() {
        assertThat(new TransportDirectory(List.of(temp.resolve("nothing"))).byName()).isEmpty();
    }
}
