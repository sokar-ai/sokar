package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The key somebody actually uses every day.
 * <p>
 * Measured against keys written by {@code ssh-keygen} and verified with the JDK's own verifier,
 * because what is being read is somebody else's format and what is produced has to be accepted by
 * somebody else's server. A fixture would only prove this agrees with itself.
 */
class RsaKeyTest {

    private Path keygen(final Path dir, final String name, final String... extra)
            throws IOException, InterruptedException {
        final Path file = dir.resolve(name);
        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                "ssh-keygen", "-q", "-f", file.toString(), "-C", "daily@work", "-N", ""));
        command.addAll(java.util.List.of(extra));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        assertThat(process.waitFor()).as("ssh-keygen").isZero();
        return file;
    }

    @Test
    void readsAnRsaKeyAndOffersThePublicHalfSshItselfWouldPrint(@TempDir final Path dir)
            throws Exception {

        final Path file = keygen(dir, "id_rsa", "-t", "rsa", "-b", "2048", "-m", "PEM");

        final AgentKey key = StoredKey.of(Files.readString(file), "daily@work");

        // The proof is not that it parsed: it is that the public half this offers is the one the
        // forge was told about, byte for byte.
        final String expected = Files.readString(dir.resolve("id_rsa.pub")).strip().split("\\s+")[1];
        assertThat(Base64.getEncoder().encodeToString(key.keyBlob())).isEqualTo(expected);
        assertThat(key.authorizedKeysLine()).startsWith("ssh-rsa ");
    }

    @Test
    void signsWithTheHashTheClientAsksFor(@TempDir final Path dir) throws Exception {

        // The part that fails silently: GitHub stopped accepting SHA-1 signatures in 2022, so an
        // agent that ignores the flags answers something the forge rejects without saying why.
        final Path file = keygen(dir, "id_rsa", "-t", "rsa", "-b", "2048", "-m", "PEM");
        final AgentKey key = StoredKey.of(Files.readString(file), "daily@work");
        final byte[] data = "what ssh would have signed".getBytes(StandardCharsets.UTF_8);

        assertThat(algorithmIn(key.sign(data, AgentKey.RSA_SHA2_512)))
                .isEqualTo("rsa-sha2-512");
        assertThat(algorithmIn(key.sign(data, AgentKey.RSA_SHA2_256)))
                .isEqualTo("rsa-sha2-256");
        // No flags is an old client, and answering it honestly is right.
        assertThat(algorithmIn(key.sign(data, 0))).isEqualTo("ssh-rsa");
    }

    @Test
    void producesASignatureAVerifierAccepts(@TempDir final Path dir) throws Exception {

        final Path file = keygen(dir, "id_rsa", "-t", "rsa", "-b", "2048", "-m", "PEM");
        final AgentKey key = StoredKey.of(Files.readString(file), "daily@work");
        final byte[] data = "sign me".getBytes(StandardCharsets.UTF_8);

        final byte[] blob = key.sign(data, AgentKey.RSA_SHA2_512);
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(blob);
        SshWire.readString(buffer);
        final byte[] signature = SshWire.readString(buffer);

        final java.security.Signature verifier =
                java.security.Signature.getInstance("SHA512withRSA");
        verifier.initVerify(publicHalfOf(dir.resolve("id_rsa.pub")));
        verifier.update(data);
        assertThat(verifier.verify(signature)).isTrue();
    }

    @Test
    void stillReadsAnEd25519SeedWrittenBeforeAnyOfThis(@TempDir final Path dir) throws Exception {

        // Every entry written until now is a seed, and nobody should have to store their key
        // again because we learned a second format.
        final Path file = keygen(dir, "id_ed25519", "-t", "ed25519");
        final String seed = OpenSshPrivateKey.seedBase64(Files.readString(file));

        assertThat(StoredKey.of(seed, "old@entry")).isInstanceOf(SigningKey.class);
        assertThat(StoredKey.of(seed, "old@entry").authorizedKeysLine())
                .startsWith("ssh-ed25519 ");
    }

    @Test
    void keepsTheSeedForEd25519AndTheFileForEverythingElse(@TempDir final Path dir)
            throws Exception {

        final String ed = Files.readString(keygen(dir, "id_ed25519", "-t", "ed25519"));
        final String rsa = Files.readString(keygen(dir, "id_rsa", "-t", "rsa", "-b", "2048",
                "-m", "PEM"));

        assertThat(StoredKey.toStore(ed)).hasSize(44).doesNotContain("BEGIN");
        assertThat(StoredKey.toStore(rsa)).contains("BEGIN RSA PRIVATE KEY");
    }

    @Test
    void saysWhatToDoWithAnOpenSshWrappedRsaKey(@TempDir final Path dir) throws Exception {

        // ssh-keygen writes this by default now, and it is not a format this reads. Saying the
        // one command that converts it is worth more than a parser nobody asked for.
        final Path file = keygen(dir, "id_rsa_new", "-t", "rsa", "-b", "2048");

        assertThatThrownBy(() -> StoredKey.of(Files.readString(file), "new@style"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("ssh-keygen -p -m PEM");
    }

    private static String algorithmIn(final byte[] blob) {
        return new String(SshWire.readString(java.nio.ByteBuffer.wrap(blob)),
                StandardCharsets.US_ASCII);
    }

    private static java.security.PublicKey publicHalfOf(final Path pub) throws Exception {
        final java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(
                Base64.getDecoder().decode(Files.readString(pub).strip().split("\\s+")[1]));
        SshWire.readString(buffer);
        final java.math.BigInteger exponent =
                new java.math.BigInteger(1, SshWire.readString(buffer));
        final java.math.BigInteger modulus =
                new java.math.BigInteger(1, SshWire.readString(buffer));
        return java.security.KeyFactory.getInstance("RSA").generatePublic(
                new java.security.spec.RSAPublicKeySpec(modulus, exponent));
    }
}
