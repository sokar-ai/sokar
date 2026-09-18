package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.sokar.vault.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link HostKey}.
 */
class HostKeyTest {

    @Test
    void creates_one_the_first_time_and_keeps_it(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("state").resolve("message-key");

        final SigningKey first = HostKey.loadOrCreate(file, "sokar@here");
        final SigningKey again = HostKey.loadOrCreate(file, "sokar@here");

        assertThat(file).exists();
        assertThat(again.keyBlob()).as("the same machine keeps the same identity")
                .isEqualTo(first.keyBlob());
    }

    @Test
    void nobody_else_on_the_machine_can_read_it(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("state").resolve("message-key");

        HostKey.loadOrCreate(file, "sokar@here");

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
    }

    @Test
    void writes_an_allowed_signers_line_a_peer_can_paste(@TempDir final Path dir)
            throws IOException {
        final SigningKey key = HostKey.loadOrCreate(dir.resolve("message-key"), "sokar@here");

        final String line = HostKey.allowedSignersLine(key, "sokar@laptop");

        assertThat(line).startsWith("sokar@laptop ssh-ed25519 ").doesNotContain("\n");
    }

    @Test
    void nothing_half_written_is_left_behind(@TempDir final Path dir) throws IOException {
        HostKey.loadOrCreate(dir.resolve("message-key"), "sokar@here");

        try (var entries = Files.list(dir)) {
            assertThat(entries.map(path -> path.getFileName().toString()))
                    .containsExactly("message-key");
        }
    }
}
