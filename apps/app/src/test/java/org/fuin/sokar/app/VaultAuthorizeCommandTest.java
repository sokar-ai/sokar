package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.supervisor.DeviceGrant;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.KernelKeyring;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for what a grant is kept as - one decision, which the terminal and the daemon's {@code Authorize} share.
 */
class VaultAuthorizeCommandTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @Test
    void aTokenThatNeverExpiresIsKeptAsItIsAndOneThatExpiresWithNothingToRenewItIsRefused(@TempDir Path dir) {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = new SokarContext(new FakeCommandRunner(),
                new SokarPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")), arguments -> 0);
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);

            final VaultAuthorizeCommand.Kept lasting = VaultAuthorizeCommand.keepGranted(context, "copilot",
                    new DeviceGrant.Outcome("granted", null, "gho_1", null));
            final VaultAuthorizeCommand.Kept hourly = VaultAuthorizeCommand.keepGranted(context, "hourly",
                    new DeviceGrant.Outcome("granted", null, "at-1", Duration.ofHours(1)));
            final VaultAuthorizeCommand.Kept renewed = VaultAuthorizeCommand.keepGranted(context, "forge",
                    new DeviceGrant.Outcome("granted", "rt-1", "at-1", Duration.ofHours(1)));

            assertThat(lasting.kept()).isTrue();
            assertThat(lasting.detail()).contains("does not expire").contains("cannot revoke");
            assertThat(hourly.kept()).isFalse();
            assertThat(hourly.detail()).contains("no refresh token");
            assertThat(renewed).isEqualTo(new VaultAuthorizeCommand.Kept(true, ""));
            final Map<String, org.fuin.sokar.vault.VaultEntry> grants = context.readableGrants().orElseThrow();
            assertThat(grants.get("copilot").type()).isEqualTo(VaultAuthorizeCommand.HELD);
            assertThat(grants.get("copilot").value()).isEqualTo("gho_1");
            assertThat(grants.get("forge").type()).isEqualTo("refresh-token");
            assertThat(grants).doesNotContainKey("hourly");
        } finally {
            keyring.forget();
        }
    }
}
