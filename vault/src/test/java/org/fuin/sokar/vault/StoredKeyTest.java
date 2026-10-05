package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link StoredKey}: a key file that is not what it claims is refused in words, never as a stack trace or
 * as a key padded with zeros.
 */
class StoredKeyTest {

    private static String pem(final byte[] der) {
        return "-----BEGIN RSA PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END RSA PRIVATE KEY-----\n";
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // A length field that ends before its bytes do.
        "MIQA",
        // An integer longer than what is left: it was padded with zeros and accepted.
        "MBACAQACIAE=",
        // A length of more bytes than a length can have.
        "MIkBAQEBAQEBAQE="})
    void aTruncatedOrImpossibleKeyIsRefusedInWords(final String body) {

        assertThatThrownBy(() -> StoredKey.of(pem(Base64.getDecoder().decode(body)), "a key"))
                .isInstanceOf(VaultException.class);
    }

    @org.junit.jupiter.api.Test
    void aRealKeyMissingItsLastByteIsRefusedNotPaddedWithZeros() throws Exception {

        // Padded, the last number was wrong and the key accepted: 'vault put' said this machine could sign with it,
        // and the forge refused every signature it made.
        final java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        final byte[] pkcs8 = generator.generateKeyPair().getPrivate().getEncoded();
        // PKCS#8 wraps PKCS#1 in an octet string after the algorithm: the inner sequence is the second '30 82'.
        int inner = -1;
        for (int at = 4; at < pkcs8.length - 1; at++) {
            if ((pkcs8[at] & 0xFF) == 0x30 && (pkcs8[at + 1] & 0xFF) == 0x82) {
                inner = at;
                break;
            }
        }
        final byte[] truncated = java.util.Arrays.copyOfRange(pkcs8, inner, pkcs8.length - 1);

        assertThatThrownBy(() -> StoredKey.of(pem(truncated), "a key")).isInstanceOf(VaultException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-----BEGIN RSA PRIVATE KEY-----\n!!!!\n-----END RSA PRIVATE KEY-----\n"})
    void aBodyThatIsNotBase64IsRefusedInWords(final String text) {

        assertThatThrownBy(() -> StoredKey.of(text, "a key")).isInstanceOf(VaultException.class);
    }
}
