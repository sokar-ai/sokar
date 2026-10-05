package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for reading the seed out of the key file a person actually has.
 * <p>
 * Measured against keys written by {@code ssh-keygen} rather than against a fixture, because the
 * thing being read is somebody else's format and a fixture would only prove this agrees with
 * itself.
 */
class OpenSshPrivateKeyTest {

    private Path keygen(final Path dir, final String name, final String... extra)
            throws IOException, InterruptedException {
        final Path file = dir.resolve(name);
        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                "ssh-keygen", "-q", "-f", file.toString(), "-C", "assigned@company"));
        command.addAll(java.util.List.of(extra));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor()).as("ssh-keygen").isZero();
        return file;
    }

    @Test
    void readsTheSeedOutOfAKeySomebodyWasAssigned(@TempDir final Path dir) throws Exception {

        final Path file = keygen(dir, "id_ed25519", "-t", "ed25519", "-N", "");

        final String seed = OpenSshPrivateKey.seedBase64(Files.readString(file));

        // The proof is not the length: it is that the key this makes has the same public half as
        // the file's own .pub, which is what a forge was told to authorise.
        final SigningKey key = new SigningKey(java.util.Base64.getDecoder().decode(seed), "x");
        assertThat(key.authorizedKeysLine())
                .startsWith(Files.readString(dir.resolve("id_ed25519.pub")).split(" ")[0] + " "
                        + Files.readString(dir.resolve("id_ed25519.pub")).split(" ")[1]);
    }

    @Test
    void refusesAKeyWithAPassphraseAndSaysWhatToDo(@TempDir final Path dir) throws Exception {

        final Path file = keygen(dir, "id_locked", "-t", "ed25519", "-N", "a-passphrase");

        assertThatThrownBy(() -> OpenSshPrivateKey.seedBase64(Files.readString(file)))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("protected by a passphrase")
                .hasMessageContaining("ssh-keygen -p");
    }

    @Test
    void refusesAnAlgorithmThisCannotSignWith(@TempDir final Path dir) throws Exception {

        // Stored happily and failing later is the behaviour this replaces.
        final Path file = keygen(dir, "id_rsa", "-t", "rsa", "-b", "2048", "-N", "");

        assertThatThrownBy(() -> OpenSshPrivateKey.seedBase64(Files.readString(file)))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("ssh-keygen -t ed25519");
    }

    @Test
    void saysSoWhenGivenThePublicHalf(@TempDir final Path dir) throws Exception {

        // The easiest mistake to make, and one where a vague error costs an afternoon.
        keygen(dir, "id_ed25519", "-t", "ed25519", "-N", "");

        assertThatThrownBy(() -> OpenSshPrivateKey.seedBase64(
                Files.readString(dir.resolve("id_ed25519.pub"))))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("private half");
    }

    @Test
    void aFieldClaimingAlmostTwoGigabytesIsRefusedInWords() {

        // 'at + length' overflowed past the check, and the copy then threw something nobody translated.
        final java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream();
        blob.writeBytes("openssh-key-v1\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        blob.writeBytes(new byte[] {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xF0, 'n', 'o', 'n', 'e'});
        final String text = "-----BEGIN OPENSSH PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder().encodeToString(blob.toByteArray())
                + "\n-----END OPENSSH PRIVATE KEY-----\n";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenSshPrivateKey.seedBase64(text))
                .isInstanceOf(VaultException.class).hasMessageContaining("ends in the middle of a field");
    }

    @Test
    void knowsAKeyFileWhenItSeesOne(@TempDir final Path dir) throws Exception {
        assertThat(OpenSshPrivateKey.looksLikeOne(
                Files.readString(keygen(dir, "id_ed25519", "-t", "ed25519", "-N", "")))).isTrue();
        assertThat(OpenSshPrivateKey.looksLikeOne("ghp_averyordinarylookingtoken")).isFalse();
    }
}
