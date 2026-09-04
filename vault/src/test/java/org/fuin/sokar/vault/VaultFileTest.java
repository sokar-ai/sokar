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
        // the file, and bounding the values is the only defence.
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
    void spotsAValueTooShortToBeACredential() {

        // The 8-character placeholder that reached a real vault and failed as an auth error.
        assertThat(VaultEntry.of("testtest").implausiblyShort()).isTrue();
        assertThat(VaultEntry.of("sk-ant-" + "x".repeat(40)).implausiblyShort()).isFalse();
    }
}
