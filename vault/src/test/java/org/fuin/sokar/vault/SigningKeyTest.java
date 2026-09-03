package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SigningKey}.
 */
class SigningKeyTest {

    @Test
    void signsWhatItCanVerify() {

        final SigningKey key = SigningKey.generate("test");
        final byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

        assertThat(key.verify(data, key.sign(data))).isTrue();
    }

    @Test
    void doesNotVerifySomethingElsesSignature() {

        final byte[] data = "hello".getBytes(StandardCharsets.UTF_8);

        assertThat(SigningKey.generate("a").verify(data, SigningKey.generate("b").sign(data)))
                .isFalse();
    }

    @Test
    void doesNotVerifyDifferentData() {

        final SigningKey key = SigningKey.generate("test");
        final byte[] signature = key.sign("hello".getBytes(StandardCharsets.UTF_8));

        assertThat(key.verify("goodbye".getBytes(StandardCharsets.UTF_8), signature)).isFalse();
    }

    @Test
    void survivesARoundTripThroughTheVault() {

        final SigningKey original = SigningKey.generate("test");
        final SigningKey restored =
                new SigningKey(Base64.getDecoder().decode(original.seedBase64()), "test");

        assertThat(restored.keyBlob()).isEqualTo(original.keyBlob());
        final byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        assertThat(original.verify(data, restored.sign(data))).isTrue();
    }

    @Test
    void producesAnAuthorizedKeysLine() {

        assertThat(SigningKey.generate("sokar@host").authorizedKeysLine())
                .startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5")
                .endsWith(" sokar@host");
    }

    @Test
    void rejectsASeedOfTheWrongSize() {

        assertThatThrownBy(() -> new SigningKey(new byte[16], "test"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("32 bytes");
    }
}
