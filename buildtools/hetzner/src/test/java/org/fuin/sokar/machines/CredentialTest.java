package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Credential}.
 */
class CredentialTest {

    @Test
    void takesTheCarriageReturnsOutOfAKey() throws IOException {
        // A stray carriage return makes an otherwise valid key fail as "error in libcrypto",
        // naming neither the file nor the reason. It reached a key file once because the cleaning
        // lived in one copy of a script and not in another.
        final Credential credential = new Credential.InMemory("-----BEGIN-----\r\nabc\r\n-----END-----");
        assertThat(credential.material()).isEqualTo("-----BEGIN-----\nabc\n-----END-----\n");
        assertThat(credential.material()).doesNotContain("\r");
    }

    @Test
    void alwaysEndsWithExactlyOneNewline() throws IOException {
        assertThat(new Credential.InMemory("key").material()).isEqualTo("key\n");
        assertThat(new Credential.InMemory("key\n\n\n").material()).isEqualTo("key\n");
        assertThat(new Credential.InMemory("  key  ").material()).isEqualTo("key\n");
    }

    @Test
    void refusesAKeyThatIsNotThere() {
        assertThatThrownBy(() -> new Credential.InMemory(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No private key");
    }

    @Test
    void cleansAKeyFromAFileTheSameWay(@TempDir Path directory) throws IOException {
        final Path file = directory.resolve("key");
        Files.writeString(file, "from-a-file\r\n");
        assertThat(new Credential.InFile(file).material()).isEqualTo("from-a-file\n");
    }

    @Test
    void saysWhereItLookedWhenThereIsNoKeyFile(@TempDir Path directory) {
        final Path missing = directory.resolve("absent");
        assertThatThrownBy(() -> new Credential.InFile(missing).material())
                .isInstanceOf(IOException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    void prefersTheMaterialOverTheFile(@TempDir Path directory) throws IOException {
        final Path file = directory.resolve("key");
        Files.writeString(file, "from-a-file");
        // CI sets the material and has no file; a developer has a file and sets no material. A
        // machine with both is CI with somebody's checkout on it, and CI's key is the right one.
        assertThat(Credential.of("in-memory", file).material()).isEqualTo("in-memory\n");
        assertThat(Credential.of(" ", file).material()).isEqualTo("from-a-file\n");
    }

    @Test
    void refusesWhenNeitherWasGiven() {
        assertThatThrownBy(() -> Credential.of(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No private key");
    }
}
