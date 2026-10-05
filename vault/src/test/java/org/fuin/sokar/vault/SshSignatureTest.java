package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link SshSignature}.
 */
class SshSignatureTest {

    private static final String NAMESPACE = "sokar-message";

    private final SigningKey key = SigningKey.generate("test");

    private final byte[] message = "{\"messageId\":\"one\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signs_and_verifies_the_exact_bytes() {
        final String signature = SshSignature.sign(key, message, NAMESPACE);

        assertThat(signature).startsWith("-----BEGIN SSH SIGNATURE-----")
                .endsWith("-----END SSH SIGNATURE-----\n");
        assertThat(SshSignature.verify(message, signature, key.keyBlob(), NAMESPACE)).isTrue();
    }

    @Test
    void one_byte_changed_is_not_the_same_message() {
        final String signature = SshSignature.sign(key, message, NAMESPACE);
        final byte[] altered = "{\"messageId\":\"two\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(SshSignature.verify(altered, signature, key.keyBlob(), NAMESPACE)).isFalse();
    }

    /**
     * The namespace is what stops a signature over one kind of thing being replayed as a signature
     * over another - a message presented as a key list, say.
     */
    @Test
    void a_signature_for_something_else_does_not_verify_here() {
        final String signature = SshSignature.sign(key, message, "sokar-keys");

        assertThat(SshSignature.verify(message, signature, key.keyBlob(), NAMESPACE)).isFalse();
    }

    @Test
    void another_key_does_not_verify() {
        final String signature = SshSignature.sign(key, message, NAMESPACE);

        assertThat(SshSignature.verify(message, signature,
                SigningKey.generate("somebody else").keyBlob(), NAMESPACE)).isFalse();
    }

    @Test
    void says_which_key_a_signature_claims_to_be_from() {
        final String signature = SshSignature.sign(key, message, NAMESPACE);

        assertThat(SshSignature.signerOf(signature)).isEqualTo(key.keyBlob());
    }

    @Test
    void what_is_not_a_signature_verifies_as_nothing_rather_than_throwing() {
        assertThat(SshSignature.signerOf("not a signature")).isNull();
        assertThat(SshSignature.verify(message, "not a signature", key.keyBlob(), NAMESPACE))
                .isFalse();
        assertThat(SshSignature.verify(message,
                "-----BEGIN SSH SIGNATURE-----\nAAAA\n-----END SSH SIGNATURE-----\n",
                key.keyBlob(), NAMESPACE)).isFalse();
    }
}
