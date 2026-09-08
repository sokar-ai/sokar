package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link VaultFile}.
 */
class VaultFileTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    /** Offset of the Argon2 iteration count: magic (8) plus version (4). */
    private static final int HEADER_ITERATIONS_OFFSET = 12;

    /** Offset of the salt: iteration count, memory and parallelism follow the version. */
    private static final int HEADER_SALT_OFFSET = 24;

    private static final Map<String, VaultEntry> ENTRIES = Map.of(
            "github.token", VaultEntry.of("ghp_example"),
            "gitlab.token", new VaultEntry("glpat_example", "oauth"));

    @Test
    void survivesARoundTrip(@TempDir Path dir) {

        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(ENTRIES, PASSPHRASE);

        assertThat(vault.read(PASSPHRASE)).isEqualTo(ENTRIES);
    }

    @Test
    void refusesAWrongPassphrase(@TempDir Path dir) {

        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(ENTRIES, PASSPHRASE);

        assertThatThrownBy(() -> vault.read("wrong".toCharArray()))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("wrong passphrase");
    }

    @Test
    void doesNotSayWhetherItWasThePassphraseOrTheFile(@TempDir Path dir) throws IOException {

        // Distinguishing the two would tell an attacker holding the file whether a guessed
        // passphrase was close, and there is no way to tell them apart anyway: both are just a
        // tag that did not verify.
        final Path file = dir.resolve("vault.bin");
        final VaultFile vault = new VaultFile(file);
        vault.write(ENTRIES, PASSPHRASE);

        final byte[] content = Files.readAllBytes(file);
        content[content.length - 1] ^= 0x01;
        Files.write(file, content);

        assertThatThrownBy(() -> vault.read(PASSPHRASE))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("wrong passphrase, or the file has been altered");
    }

    @Test
    void storesNothingInClear(@TempDir Path dir) throws IOException {

        final Path file = dir.resolve("vault.bin");
        new VaultFile(file).write(ENTRIES, PASSPHRASE);

        final String raw = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(raw).doesNotContain("ghp_example").doesNotContain("github.token");
    }

    @Test
    void detectsATamperedHeader(@TempDir Path dir) throws IOException {

        // The header is authenticated as additional data, so an attacker cannot weaken the KDF
        // parameters of an existing file and then attack it offline.
        final Path file = dir.resolve("vault.bin");
        new VaultFile(file).write(ENTRIES, PASSPHRASE);

        final byte[] content = Files.readAllBytes(file);
        // Change the salt, not the cost parameters: those are covered by their own test below.
        content[HEADER_SALT_OFFSET] ^= 0x01;
        Files.write(file, content);

        assertThatThrownBy(() -> new VaultFile(file).read(PASSPHRASE))
                .isInstanceOf(VaultException.class);
    }

    @Test
    void refusesImplausibleKeyDerivationParameters(@TempDir Path dir) throws IOException {

        // The cost parameters are read from the file and must be used before the tag can be
        // checked - deriving the key is what makes checking possible. So a single flipped byte in
        // the iteration count turns 3 into 16777219, and the process hangs before it ever gets to
        // notice the file was altered. This is a denial of service against anyone who can write
        // the file, and bounding the values is the only defense.
        final Path file = dir.resolve("vault.bin");
        new VaultFile(file).write(ENTRIES, PASSPHRASE);

        final byte[] content = Files.readAllBytes(file);
        content[HEADER_ITERATIONS_OFFSET] = 1;
        Files.write(file, content);

        assertThatThrownBy(() -> new VaultFile(file).read(PASSPHRASE))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("implausible key-derivation parameters")
                .hasMessageContaining("rejected unread");
    }

    @Test
    void refusesAnImplausibleMemoryCost(@TempDir Path dir) throws IOException {

        // The same attack with memory instead of time: allocate until the process is killed.
        final Path file = dir.resolve("vault.bin");
        new VaultFile(file).write(ENTRIES, PASSPHRASE);

        final byte[] content = Files.readAllBytes(file);
        content[HEADER_ITERATIONS_OFFSET + 4] = 0x7f;
        Files.write(file, content);

        assertThatThrownBy(() -> new VaultFile(file).read(PASSPHRASE))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("implausible");
    }

    @Test
    void usesADifferentSaltAndNonceEveryTime(@TempDir Path dir) throws IOException {

        final Path first = dir.resolve("a.bin");
        final Path second = dir.resolve("b.bin");
        new VaultFile(first).write(ENTRIES, PASSPHRASE);
        new VaultFile(second).write(ENTRIES, PASSPHRASE);

        // Identical content under an identical passphrase must not produce identical bytes:
        // a reused GCM nonce is catastrophic, and equal files would reveal one had happened.
        assertThat(Files.readAllBytes(first)).isNotEqualTo(Files.readAllBytes(second));
    }

    @Test
    void rejectsSomethingThatIsNotAVault(@TempDir Path dir) throws IOException {

        final Path file = dir.resolve("vault.bin");
        Files.writeString(file, "this is not a vault at all, but it is long enough to have a header");

        assertThatThrownBy(() -> new VaultFile(file).read(PASSPHRASE))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("not a Sokar vault");
    }

    @Test
    void rejectsAFileTooShortToBeAVault(@TempDir Path dir) throws IOException {

        final Path file = dir.resolve("vault.bin");
        Files.write(file, new byte[3]);

        assertThatThrownBy(() -> new VaultFile(file).read(PASSPHRASE))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void writesWithOwnerOnlyPermissions(@TempDir Path dir) throws IOException {

        final Path file = dir.resolve("vault.bin");
        new VaultFile(file).write(ENTRIES, PASSPHRASE);

        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void updateAddsToWhatIsAlreadyThere(@TempDir Path dir) {

        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.update(PASSPHRASE, entries -> {
            entries.put("first", VaultEntry.of("1"));
            return entries;
        });
        vault.update(PASSPHRASE, entries -> {
            entries.put("second", VaultEntry.of("2"));
            return entries;
        });

        assertThat(vault.read(PASSPHRASE)).containsEntry("first", VaultEntry.of("1")).containsEntry("second", VaultEntry.of("2"));
    }

    @Test
    void concurrentUpdatesDoNotLoseEntries(@TempDir Path dir) throws Exception {

        // Two sokar processes storing different credentials at the same moment is not exotic: a
        // launch storm does it. Without the lock, one write silently overwrites the other.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(new LinkedHashMap<>(), PASSPHRASE);

        final ExecutorService pool = Executors.newFixedThreadPool(4);
        for (int i = 0; i < 12; i++) {
            final String name = "key" + i;
            pool.submit(() -> vault.update(PASSPHRASE, entries -> {
                entries.put(name, VaultEntry.of(name));
                return entries;
            }));
        }
        pool.shutdown();
        assertThat(pool.awaitTermination(120, TimeUnit.SECONDS)).isTrue();

        assertThat(vault.read(PASSPHRASE)).hasSize(12);
    }

    @Test
    void leavesTheOldVaultIntactIfWritingFails(@TempDir Path dir) {

        final VaultFile vault = new VaultFile(dir.resolve("sub/vault.bin"));
        vault.write(ENTRIES, PASSPHRASE);

        assertThatThrownBy(() -> vault.update(PASSPHRASE, entries -> {
            throw new IllegalStateException("deliberate");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(vault.read(PASSPHRASE)).isEqualTo(ENTRIES);
    }

    @Test
    void acceptsOnlyThePassphraseItWasWrittenWith(@TempDir Path dir) {

        // What "vault unlock" checks before caching: it used to cache anything typed, so a typo
        // surfaced later as a message about a possibly altered file.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("example", VaultEntry.of("a-secret")), PASSPHRASE);

        assertThat(vault.accepts(PASSPHRASE)).isTrue();
        assertThat(vault.accepts("wrong".toCharArray())).isFalse();
    }

    @Test
    void acceptsNothingWhenThereIsNoVault(@TempDir Path dir) {

        // The negative case: a missing file must not read as "this passphrase is fine", or the
        // check would pass for every passphrase on a machine with no vault.
        assertThat(new VaultFile(dir.resolve("absent.bin")).accepts(PASSPHRASE)).isFalse();
    }

    @Test
    void remembersWhatKindOfCredentialAnEntryIs(@TempDir Path dir) {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("one", new VaultEntry("a-long-enough-secret", "oauth")), PASSPHRASE);

        assertThat(vault.read(PASSPHRASE).get("one").type()).isEqualTo("oauth");
    }

    @Test
    void readsAVaultWrittenBeforeEntriesHadAKind(@TempDir Path dir) {

        // The compatibility case: an existing vault holds bare strings, and must keep opening.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("one", VaultEntry.of("plain")), PASSPHRASE);

        final VaultEntry entry = vault.read(PASSPHRASE).get("one");
        assertThat(entry.value()).isEqualTo("plain");
        assertThat(entry.type()).isNull();
    }

    @Test
    void spotsAValueThatIsNotACredential() {

        // All three reached a real vault. The placeholder is the one a length check misses: it is
        // 24 characters, and it is what the guide tells you to replace.
        assertThat(VaultEntry.of("<your real key or token>").suspicious())
                .contains("placeholder");
        assertThat(VaultEntry.of("testtest").suspicious()).contains("8 characters");
        assertThat(VaultEntry.of("sk ant token with spaces here").suspicious())
                .contains("spaces");
    }

    @Test
    void acceptsAValueThatLooksLikeACredential() {

        // The negative case: warning about a real key would teach the operator to ignore it.
        assertThat(VaultEntry.of("sk-ant-api03-" + "x".repeat(90)).suspicious()).isNull();
    }

    @Test
    void rekeyReadsWithTheOldAndWritesWithTheNew(@TempDir Path dir) {

        // The whole operation in one assertion: afterwards the old passphrase does not open it
        // and the new one does. Until this existed the product could not change a passphrase at
        // all, and the only way round was to store every credential again - which needs the
        // values, which this file deliberately will not hand back.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("anthropic", VaultEntry.of("sk-test-value")), "old one".toCharArray());

        vault.rekey("old one".toCharArray(), "new one".toCharArray());

        assertThat(vault.accepts("old one".toCharArray())).isFalse();
        assertThat(vault.accepts("new one".toCharArray())).isTrue();
    }

    @Test
    void rekeyKeepsEveryEntryExactly(@TempDir Path dir) {

        // Re-encrypting must not be a way to lose a credential. There is no second copy anywhere,
        // so anything dropped here is gone for good.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final Map<String, VaultEntry> before = Map.of(
                "anthropic", new VaultEntry("sk-one", "api-key"),
                "openai", new VaultEntry("sk-two", "api-key"),
                "github", VaultEntry.of("ghp-three"));
        vault.write(before, "old one".toCharArray());

        vault.rekey("old one".toCharArray(), "new one".toCharArray());

        assertThat(vault.read("new one".toCharArray())).isEqualTo(before);
    }

    @Test
    void rekeyWithTheWrongCurrentPassphraseChangesNothing(@TempDir Path dir) {

        // Fails on the read, before anything is written. A vault re-encrypted under a passphrase
        // derived from a failed read would be a vault nobody can open.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("anthropic", VaultEntry.of("sk-test-value")), "old one".toCharArray());

        assertThatThrownBy(() -> vault.rekey("wrong".toCharArray(), "new one".toCharArray()))
                .isInstanceOf(VaultException.class);

        assertThat(vault.accepts("old one".toCharArray()))
                .as("the vault still opens with what it always did").isTrue();
    }

    @Test
    void theSaltBelongsToTheVaultAndNotToEachWrite(@TempDir Path dir) throws Exception {

        // It used to be regenerated every time anything was stored, which cost a full Argon2id
        // derivation - 64 MiB, three passes - per credential, bought nothing identifiable, and
        // made it impossible to cache anything derived from the passphrase.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("one", VaultEntry.of("a")), "pass".toCharArray());
        final byte[] first = saltOf(dir.resolve("vault.bin"));

        vault.write(Map.of("one", VaultEntry.of("a"), "two", VaultEntry.of("b")),
                "pass".toCharArray());

        assertThat(saltOf(dir.resolve("vault.bin"))).isEqualTo(first);
        assertThat(vault.read("pass".toCharArray())).containsKeys("one", "two");
    }

    @Test
    void theNonceStillChangesOnEveryWrite(@TempDir Path dir) throws Exception {

        // Uniqueness of the encryption comes from here, not from the salt. Reusing a nonce with
        // one key is the failure that breaks AES-GCM outright, so keeping the salt must not have
        // quietly frozen this too.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("one", VaultEntry.of("a")), "pass".toCharArray());
        final byte[] first = nonceOf(dir.resolve("vault.bin"));

        vault.write(Map.of("one", VaultEntry.of("b")), "pass".toCharArray());

        assertThat(nonceOf(dir.resolve("vault.bin"))).isNotEqualTo(first);
    }

    @Test
    void changingThePassphraseDoesGetANewSalt(@TempDir Path dir) throws Exception {

        // The one case where keeping it would preserve an attacker's precomputation across the
        // change.
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("one", VaultEntry.of("a")), "old".toCharArray());
        final byte[] before = saltOf(dir.resolve("vault.bin"));

        vault.rekey("old".toCharArray(), "new".toCharArray());

        assertThat(saltOf(dir.resolve("vault.bin"))).isNotEqualTo(before);
        assertThat(vault.read("new".toCharArray())).containsKey("one");
    }

    @Test
    void aFirstWriteGeneratesASaltRatherThanFailing(@TempDir Path dir) throws Exception {

        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(Map.of("one", VaultEntry.of("a")), "pass".toCharArray());

        assertThat(saltOf(dir.resolve("vault.bin"))).isNotEmpty()
                .isNotEqualTo(new byte[16]);
    }

    @Test
    void theDocumentBufferIsOverwrittenRatherThanLeftForTheCollector() {

        // Building the document used to hand back a String holding every credential in plaintext,
        // which cannot be cleared. It is written into a buffer this owns instead - and a buffer
        // that is owned and not cleared would be the same exposure with more code.
        final StringBuilder text = new StringBuilder("sk-secret-value");

        VaultFile.wipe(text);

        // Emptying it would leave the characters in the backing array, which is the exposure
        // rather than the length.
        assertThat(text.toString()).isEqualTo("\0".repeat("sk-secret-value".length()));
    }

    /** The salt, read straight out of the header where it sits in the clear. */
    private static byte[] saltOf(Path file) throws Exception {
        return java.util.Arrays.copyOfRange(java.nio.file.Files.readAllBytes(file), 24, 40);
    }

    /** The nonce, immediately after the salt. */
    private static byte[] nonceOf(Path file) throws Exception {
        return java.util.Arrays.copyOfRange(java.nio.file.Files.readAllBytes(file), 40, 52);
    }
}
