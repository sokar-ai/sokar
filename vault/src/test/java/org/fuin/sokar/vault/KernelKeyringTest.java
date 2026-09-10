package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Duration;
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

        assertThat(keyring.forget()).isEqualTo(KernelKeyring.Forgotten.CLEARED);
        assertThat(keyring.read()).isEmpty();
    }

    @Test
    void forgettingSomethingThatIsNotThereIsNotAnError() {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        assertThat(new KernelKeyring("sokar-absent-" + UUID.randomUUID()).forget())
                .isEqualTo(KernelKeyring.Forgotten.NOTHING_CACHED);
    }

    @Test
    void tellsAbsenceApartFromNotBeingAbleToTell() {
        // The search returns -1 for any reason at all, and treating that as "nothing was cached"
        // told an operator a passphrase was gone while it was still in the keyring. Absence,
        // expiry and revocation are answers; every other errno is not.
        assertThat(KernelKeyring.missing(126)).as("ENOKEY, nothing there").isTrue();
        assertThat(KernelKeyring.missing(127)).as("EKEYEXPIRED, and --for makes this ordinary")
                .isTrue();
        assertThat(KernelKeyring.missing(128)).as("EKEYREVOKED, as pam_keyinit does at logout")
                .isTrue();
        assertThat(KernelKeyring.missing(13)).as("EACCES settles nothing about the secret")
                .isFalse();
        assertThat(KernelKeyring.missing(12)).as("ENOMEM settles nothing either").isFalse();
        assertThat(KernelKeyring.missing(0)).as("no errno at all is not absence").isFalse();
    }

    @Test
    void findsWhatWasStoredEvenWithoutTheSessionLink() {
        // The key is added to the user keyring and was searched for in the session keyring, which
        // finds it only through a link - and pam_keyinit revokes a session keyring at logout, so
        // a process outliving its login searched a revoked keyring while the key sat in @u.
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        keyring.store("secret".toCharArray());
        assertThat(keyring.read()).isPresent();
        assertThat(keyring.forget()).isEqualTo(KernelKeyring.Forgotten.CLEARED);
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
        vault.write(java.util.Map.of("k", VaultEntry.of("v")), "typed once".toCharArray());
        keyring.store("typed once".toCharArray());

        final PassphraseTiers tiers = new PassphraseTiers(KernelKeyring.source(description));

        assertThat(vault.read(tiers.require())).containsEntry("k", VaultEntry.of("v"));
    }

    @Test
    void aBoundedPassphraseIsDiscardedByTheKernelItself() throws InterruptedException {

        // The whole point of a bound: nothing in Sokar has to remember to drop it, no timer runs,
        // and a process that dies leaves nothing behind that outlives its welcome. Measured
        // against the real kernel, because "we asked for a timeout" is not the same claim as
        // "the passphrase is gone".
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        keyring.store("correct horse battery staple".toCharArray(), Duration.ofSeconds(1));
        assertThat(keyring.read()).as("stored, before the bound runs out").isPresent();

        for (int i = 0; i < 60 && keyring.read().isPresent(); i++) {
            Thread.sleep(100);
        }

        assertThat(keyring.read()).as("after the bound ran out").isEmpty();
    }

    @Test
    void withoutABoundThePassphraseStays() throws InterruptedException {

        // The default is unchanged: one behaviour, kept until it is dropped or the session ends.
        // A bound that crept in as a default would start asking people for a passphrase they
        // never used to be asked for.
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        keyring.store("correct horse battery staple".toCharArray(), null);
        Thread.sleep(1200);

        assertThat(keyring.read()).isPresent();
    }
}
