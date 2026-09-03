package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.fuin.sokar.core.process.FakeCommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SystemdCredential}.
 */
class SystemdCredentialTest {

    @Test
    void decryptsAConfiguredCredential() {

        final FakeCommandRunner runner = new FakeCommandRunner()
                .answering("systemd-creds decrypt", "sealed-passphrase\n");

        assertThat(new SystemdCredential(runner, "/etc/sokar/vault.cred").passphrase())
                .hasValueSatisfying(value ->
                        assertThat(value).containsExactly("sealed-passphrase".toCharArray()));
    }

    @Test
    void writesToStandardOutputRatherThanAFile() {

        final FakeCommandRunner runner = new FakeCommandRunner()
                .answering("systemd-creds decrypt", "x\n");

        new SystemdCredential(runner, "/etc/sokar/vault.cred").passphrase();

        // Decrypting to a file would put the plaintext on disk, which is the one thing this tier
        // exists to avoid.
        assertThat(runner.only("systemd-creds").describe())
                .isEqualTo("systemd-creds decrypt /etc/sokar/vault.cred -");
    }

    @Test
    void skipsWhenNoCredentialIsConfigured() {

        assertThat(new SystemdCredential(new FakeCommandRunner(), null).passphrase()).isEmpty();
    }

    @Test
    void refusesToFallThroughWhenAConfiguredCredentialWillNotDecrypt() {

        // Almost always a TPM state change. Prompting instead would hide a real problem behind a
        // password box.
        final FakeCommandRunner runner = new FakeCommandRunner()
                .failing("systemd-creds decrypt", 1, "Failed to decrypt credential: TPM2 policy mismatch");

        assertThatThrownBy(() -> new SystemdCredential(runner, "/etc/sokar/vault.cred").passphrase())
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("TPM2 policy mismatch");
    }

    @Test
    void skipsSilentlyOnAHostWithoutSystemd() {

        // systemd-creds not existing is not a misconfiguration, and must not stop the tiers below
        // it from being tried. Simulated rather than run for real: this host has systemd-creds,
        // so the real runner would exercise the decrypt-failure path instead.
        final org.fuin.sokar.core.process.CommandRunner absent = command -> {
            throw new org.fuin.sokar.core.process.CommandException(command,
                    new java.io.IOException("Cannot run program \"systemd-creds\""));
        };

        assertThat(new SystemdCredential(absent, "/etc/sokar/vault.cred").passphrase()).isEmpty();
    }

    @Test
    void sealsThroughStandardInput() {

        final FakeCommandRunner runner = new FakeCommandRunner();

        new SystemdCredential(runner, null).seal("secret".toCharArray(), "/tmp/v.cred", "sokar.vault");

        final var command = runner.only("systemd-creds");
        assertThat(command.describe())
                .isEqualTo("systemd-creds encrypt --name=sokar.vault - /tmp/v.cred");
        // Passing the passphrase as an argument would put it in every process listing on the host.
        assertThat(command.input()).isEqualTo("secret");
    }
}
