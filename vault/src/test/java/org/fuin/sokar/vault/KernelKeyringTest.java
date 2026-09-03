package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link KernelKeyring}, against the real kernel.
 * <p>
 * A unique description per run, so a failing test cannot leave something behind that a later run
 * then reads back and passes on.
 */
class KernelKeyringTest {

    private final String description = "sokar-test-" + UUID.randomUUID();

    private final KernelKeyring keyring = new KernelKeyring(description);

    @AfterEach
    void tidyUp() {
        keyring.forget();
    }

    @Test
    void storesAndReadsBackAPassphrase() {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        keyring.store("correct horse battery staple".toCharArray());

        assertThat(keyring.read()).hasValueSatisfying(value ->
                assertThat(value).containsExactly("correct horse battery staple".toCharArray()));
    }

    @Test
    void readsNothingBeforeAnythingIsStored() {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        // The ordinary case before the first unlock: empty, not an error.
        assertThat(new KernelKeyring("sokar-never-stored-" + UUID.randomUUID()).read()).isEmpty();
    }

    @Test
    void forgettingRemovesIt() {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        keyring.store("secret".toCharArray());
        assertThat(keyring.read()).isPresent();

        assertThat(keyring.forget()).isTrue();
        assertThat(keyring.read()).isEmpty();
    }

    @Test
    void forgettingSomethingThatIsNotThereIsNotAnError() {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        assertThat(new KernelKeyring("sokar-absent-" + UUID.randomUUID()).forget()).isFalse();
    }

    @Test
    void replacingOverwritesTheOldValue() {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        keyring.store("first".toCharArray());
        keyring.store("second".toCharArray());

        assertThat(keyring.read()).hasValueSatisfying(value ->
                assertThat(value).containsExactly("second".toCharArray()));
    }

    @Test
    void survivesANonAsciiPassphrase() {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        // The payload is bytes, so anything that is not encoded and decoded consistently comes
        // back as a passphrase that no longer opens the vault.
        final char[] passphrase = "Straße-\u00e9\u00e8-\u4f60\u597d".toCharArray();
        keyring.store(passphrase);

        assertThat(keyring.read()).hasValueSatisfying(value ->
                assertThat(value).containsExactly(passphrase));
    }

    @Test
    void worksAsAPassphraseTier() {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        keyring.store("tiered".toCharArray());

        assertThat(KernelKeyring.source(description).passphrase()).isPresent();
        assertThat(KernelKeyring.source(description).name()).isEqualTo("kernel-keyring");
    }

    @Test
    void unlocksTheVaultItStoredThePassphraseFor(@org.junit.jupiter.api.io.TempDir
            java.nio.file.Path dir) {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        // The point of the tier: unlock once per login rather than once per command.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(java.util.Map.of("k", "v"), "typed once".toCharArray());
        keyring.store("typed once".toCharArray());

        final PassphraseTiers tiers = new PassphraseTiers(KernelKeyring.source(description));

        assertThat(vault.read(tiers.require())).containsEntry("k", "v");
    }
}
