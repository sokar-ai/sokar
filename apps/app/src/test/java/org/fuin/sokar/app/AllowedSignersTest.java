package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.vault.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link AllowedSigners}.
 */
class AllowedSignersTest {

    private final SigningKey one = SigningKey.generate("one");

    private final SigningKey two = SigningKey.generate("two");

    @Test
    void a_machine_with_no_such_file_knows_nobody(@TempDir final Path dir) throws IOException {
        assertThat(AllowedSigners.read(dir.resolve("allowed_signers"))).isEmpty();
    }

    @Test
    void reads_what_ssh_keygen_would_read(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("allowed_signers");
        Files.writeString(file, "# our machines\n"
                + "reviewer " + one.authorizedKeysLine() + "\n"
                + "\n"
                + "ops " + two.authorizedKeysLine() + "\n");

        final var peers = AllowedSigners.read(file);

        assertThat(peers).extracting(MessageDelivery.Peer::name)
                .containsExactly("reviewer", "ops");
        assertThat(peers.get(0).keys()).singleElement().isEqualTo(one.keyBlob());
    }

    /**
     * A peer during a key rotation has two keys at once, and both have to work - otherwise the
     * rotation is a day on which messages stop arriving.
     */
    @Test
    void a_peer_may_hold_more_than_one_key(@TempDir final Path dir) throws IOException {
        final Path file = dir.resolve("allowed_signers");
        Files.writeString(file, "reviewer " + one.authorizedKeysLine() + "\n"
                + "reviewer " + two.authorizedKeysLine() + "\n");

        assertThat(AllowedSigners.read(file)).singleElement()
                .satisfies(peer -> assertThat(peer.keys()).hasSize(2));
    }

    /**
     * A file that quietly lost an entry is a file that quietly stops recognising a peer. Refusing
     * the whole file is the only answer that cannot be mistaken for "that peer is not allowed".
     */
    @Test
    void one_line_it_cannot_read_refuses_the_whole_file(@TempDir final Path dir)
            throws IOException {
        final Path file = dir.resolve("allowed_signers");
        Files.writeString(file, "reviewer " + one.authorizedKeysLine() + "\n"
                + "ops ssh-rsa AAAAB3NzaC1yc2E\n");

        assertThatThrownBy(() -> AllowedSigners.read(file)).isInstanceOf(IOException.class)
                .hasMessageContaining(":2:");
    }
}
