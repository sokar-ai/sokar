package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.KernelKeyring;
import org.fuin.sokar.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SokarContext#readableCredentials()}, against a real vault and the real kernel
 * keyring.
 * <p>
 * The four states an empty credential list can mean. {@link SokarContext#credentials()} answers
 * all four with the same empty map on purpose - a task that needs no credential must not be
 * blocked by one that is merely absent - so the only thing that can tell them apart is this, and
 * anything reporting the store's state to a person depends on it.
 */
class SokarContextTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private KernelKeyring keyring;

    @AfterEach
    void tidyUp() {
        if (keyring != null) {
            keyring.forget();
        }
    }

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context =
                new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                        arguments -> 0);
        // Keyed by the vault it unlocks, so every temporary directory gets its own description and
        // one test cannot read back what another left in the kernel.
        keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        return context;
    }

    private static void writeVault(SokarContext context, Map<String, VaultEntry> entries,
            char[] passphrase) throws IOException {
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(entries, passphrase);
    }

    @Test
    void anUnlockedVaultHoldingNothingIsReadable(@TempDir Path dir) throws IOException {

        // The case that was answered wrongly. An empty vault whose passphrase is cached reads as
        // unreadable if "readable" is inferred from the list being empty, and an interface acting
        // on that tells somebody to unlock a vault that is already open - the one instruction
        // that cannot help them.
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        final SokarContext context = context(dir);
        writeVault(context, Map.of(), PASSPHRASE);
        keyring.store(PASSPHRASE);

        assertThat(context.readableCredentials()).isPresent().get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
    }

    @Test
    void aLockedVaultIsNotReadable(@TempDir Path dir) throws IOException {

        // Nothing in the keyring, so there is no passphrase to read it with and no terminal to
        // ask at.
        final SokarContext context = context(dir);
        writeVault(context, Map.of("anthropic", VaultEntry.of("sk-test-value")), PASSPHRASE);

        assertThat(context.readableCredentials()).isEmpty();
    }

    @Test
    void aVaultThatIsNotThereIsReadableAndEmpty(@TempDir Path dir) {

        // There is nothing to unlock. Reporting this as unreadable would send somebody looking
        // for a passphrase for a file that was never created.
        assertThat(context(dir).readableCredentials()).isPresent().get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
    }

    @Test
    void aVaultThatCannotBeDecryptedIsNotReadable(@TempDir Path dir) throws IOException {

        // A wrong cached passphrase or a damaged file. The operator has something to fix, and
        // reporting it as an empty vault hides it behind a state that looks fine.
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        final SokarContext context = context(dir);
        writeVault(context, Map.of("anthropic", VaultEntry.of("sk-test-value")), PASSPHRASE);
        keyring.store("not the passphrase this vault was written with".toCharArray());

        assertThat(context.readableCredentials()).isEmpty();
    }

    @Test
    void anUnlockedVaultAnswersWhatItHolds(@TempDir Path dir) throws IOException {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        final SokarContext context = context(dir);
        writeVault(context, Map.of("anthropic", VaultEntry.of("sk-test-value")), PASSPHRASE);
        keyring.store(PASSPHRASE);

        assertThat(context.readableCredentials()).isPresent().get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsOnlyKeys("anthropic");
    }
}
