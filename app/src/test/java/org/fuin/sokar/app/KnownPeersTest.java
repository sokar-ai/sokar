package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.vault.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link KnownPeers}, which exists because there are two places a believable key comes
 * from and everything that delivers a message has to read both.
 */
class KnownPeersTest {

    private String line(final String principal) {
        return principal + " " + SigningKey.generate(principal).authorizedKeysLine();
    }

    @Test
    void reads_the_operators_own_keyring(@TempDir final Path dir) throws IOException {
        final Path keyring = dir.resolve("allowed_signers");
        Files.writeString(keyring, line("reviewer") + "\n");

        assertThat(KnownPeers.of(keyring, dir.resolve("shared")))
                .extracting(MessageDelivery.Peer::name).containsExactly("reviewer");
    }

    @Test
    void reads_the_accounts_of_this_machine_as_well(@TempDir final Path dir) throws IOException {
        final String me = System.getProperty("user.name");
        Files.createDirectories(dir.resolve("shared").resolve(me));
        Files.writeString(dir.resolve("shared").resolve(me).resolve(SharedKeys.FILE),
                line(me) + "\n");

        assertThat(KnownPeers.of(dir.resolve("none"), dir.resolve("shared")))
                .extracting(MessageDelivery.Peer::name).containsExactly(me);
    }

    /**
     * A machine where neither exists is not an error: it has no peers, which is a true answer.
     */
    @Test
    void a_machine_with_neither_has_no_peers(@TempDir final Path dir) throws IOException {
        assertThat(KnownPeers.of(dir.resolve("none"), dir.resolve("nothing"))).isEmpty();
    }

    /**
     * The operator wrote one down by hand; the shared directory fills itself from whoever is on
     * the machine. Where they disagree, the deliberate one is the one to keep.
     */
    @Test
    void the_operators_own_file_wins_a_name_they_both_claim(@TempDir final Path dir)
            throws IOException {
        final String me = System.getProperty("user.name");
        final Path keyring = dir.resolve("allowed_signers");
        final String deliberate = line(me);
        Files.writeString(keyring, deliberate + "\n");
        Files.createDirectories(dir.resolve("shared").resolve(me));
        Files.writeString(dir.resolve("shared").resolve(me).resolve(SharedKeys.FILE),
                line(me) + "\n");

        final var peers = KnownPeers.of(keyring, dir.resolve("shared"));

        assertThat(peers).hasSize(1);
        assertThat(peers.get(0).keys()).singleElement().satisfies(key ->
                assertThat(java.util.Base64.getEncoder().encodeToString(key))
                        .isEqualTo(deliberate.split("\\s+")[2]));
    }
}
