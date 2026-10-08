package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultCacheTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @TempDir
    Path dir;

    @AfterEach
    void off() {
        VaultCache.stop();
    }

    private static VaultEntry secret(final String value) {
        return VaultEntry.of(value);
    }

    @Test
    void theDaemonReadsTheVaultOnceWhileItIsUnchangedAndAgainOnceItChanges() {
        // Each lookup of a conversation's secrets decrypted the whole vault, 150 ms of CPU each: 18 % of a core with two
        // idle tasks in a conversation.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("a", secret("one")), PASSPHRASE);
        final VaultFile.Opener opener = VaultFile.Opener.passphrase(PASSPHRASE);
        VaultCache.hold();

        final Map<String, VaultEntry> first = VaultCache.read(vault, opener);
        assertThat(VaultCache.read(vault, opener)).as("unchanged: what was held").isSameAs(first);

        vault.write(Map.of("a", secret("one"), "b", secret("two")), PASSPHRASE);
        assertThat(VaultCache.read(vault, opener)).as("changed: read again").containsKey("b");
    }

    @Test
    void whatWasHeldGoesWhenTheVaultIsFoundShutAndWhenHoldingStops() {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("a", secret("one")), PASSPHRASE);
        final VaultFile.Opener opener = VaultFile.Opener.passphrase(PASSPHRASE);
        VaultCache.hold();
        final Map<String, VaultEntry> held = VaultCache.read(vault, opener);

        // The daemon's look finds the vault shut.
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final SokarContext context = new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
        new MessageWatch(context, Duration.ofMinutes(1), () -> false).opened();
        final Map<String, VaultEntry> afterTheLock = VaultCache.read(vault, opener);
        assertThat(afterTheLock).as("read again after the lock").isNotSameAs(held).isEqualTo(held);

        VaultCache.drop();
        assertThat(VaultCache.read(vault, opener)).as("dropped").isNotSameAs(afterTheLock);

        VaultCache.stop();
        final Map<String, VaultEntry> once = VaultCache.read(vault, opener);
        assertThat(VaultCache.read(vault, opener)).as("a command holds nothing").isNotSameAs(once);
    }
}
