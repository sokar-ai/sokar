package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A device opens the vault with a share, and the node cannot open it alone.
 */
class KeyslotTest {

    private static final char[] PASSPHRASE = "correct horse".toCharArray();

    private static final Map<String, VaultEntry> ENTRIES =
            Map.of("github.token", VaultEntry.of("ghp_example"));

    private byte[] share(final int seed) {
        final byte[] share = new byte[32];
        new java.util.Random(seed).nextBytes(share);
        return share;
    }

    private VaultFile vault(final Path dir) {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        vault.write(ENTRIES, PASSPHRASE);
        return vault;
    }

    @Test
    void a_new_vault_has_one_way_in_and_it_is_the_passphrase(@TempDir final Path dir) {
        assertThat(vault(dir).slots()).singleElement().satisfies(slot -> {
            assertThat(slot.recovery()).isTrue();
            assertThat(slot.id()).isEqualTo(Keyslot.PASSPHRASE);
        });
    }

    @Test
    void a_device_opens_the_vault_with_nothing_typed(@TempDir final Path dir) {
        final VaultFile vault = vault(dir);
        final byte[] share = share(1);

        final Keyslot slot = vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), share,
                "the laptop", "USER_SCOPED");

        assertThat(vault.read(share)).containsKey("github.token");
        assertThat(slot.recovery()).isFalse();
        assertThat(vault.slots()).hasSize(2);
    }

    @Test
    void the_passphrase_still_opens_it_with_no_device_present(@TempDir final Path dir) {
        final VaultFile vault = vault(dir);
        vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), share(1), "the laptop",
                "USER_SCOPED");

        assertThat(vault.read(PASSPHRASE)).containsKey("github.token");
    }

    /**
     * The acceptance asks for this to be asserted rather than argued: after enrollment the node
     * keeps nothing it could derive the wrapping key from again.
     */
    @Test
    void the_nodes_copy_of_the_share_is_gone(@TempDir final Path dir) throws Exception {
        final VaultFile vault = vault(dir);
        final byte[] share = share(1);

        vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), share, "the laptop", "USER_SCOPED");

        final byte[] stored = Files.readAllBytes(dir.resolve("vault.bin"));
        assertThat(indexOf(stored, share)).as("the share itself is not in the file").isEqualTo(-1);
        for (final Path each : Files.list(dir).toList()) {
            assertThat(indexOf(Files.readAllBytes(each), share))
                    .as("the share is not in " + each.getFileName()).isEqualTo(-1);
        }
    }

    /**
     * The property the whole design rests on: if the node could unwrap with only what it stores, a
     * copy of this machine would be a copy of the vault and enrolling a device would be theatre.
     * <p>
     * <strong>What this test shows on its own is narrower than the property</strong>, and saying so
     * matters: it shows that a share other than the enrolled one does not open the slot, including
     * the all-zero one. The property itself is carried by this together with
     * {@link #the_nodes_copy_of_the_share_is_gone()} and by the construction - the wrapping key is
     * {@code HKDF(share, salt)}, the salt is public, and the share is nowhere on this machine. No
     * test can enumerate everything the node does not have; what it can do is prove the share is
     * not among what it has.
     */
    @Test
    void the_node_alone_cannot_unwrap(@TempDir final Path dir) {
        final VaultFile vault = vault(dir);
        final byte[] share = share(1);
        vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), share, "the laptop", "USER_SCOPED");

        // Everything the node stores, and no share: the file, and the slot's own salt and nonce.
        final byte[] anotherShare = share(2);

        assertThatThrownBy(() -> vault.read(anotherShare))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("No keyslot");
        // And the slot's salt is public, which must not be enough either.
        assertThatThrownBy(() -> vault.read(new byte[32]))
                .isInstanceOf(VaultException.class);
    }

    @Test
    void revoking_a_device_leaves_every_other_way_in(@TempDir final Path dir) {
        final VaultFile vault = vault(dir);
        final byte[] one = share(1);
        final byte[] two = share(2);
        final Keyslot first = vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), one,
                "the laptop", "USER_SCOPED");
        vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), two, "the phone",
                "APPLICATION_SCOPED");

        final var remaining = vault.revoke(VaultFile.Opener.passphrase(PASSPHRASE), first.id());

        assertThat(remaining).extracting(Keyslot::name).containsExactly("passphrase", "the phone");
        assertThat(vault.read(two)).containsKey("github.token");
        assertThat(vault.read(PASSPHRASE)).containsKey("github.token");
        assertThatThrownBy(() -> vault.read(one)).isInstanceOf(VaultException.class);
    }

    @Test
    void the_last_way_in_cannot_be_revoked(@TempDir final Path dir) {
        final VaultFile vault = vault(dir);

        assertThatThrownBy(() -> vault.revoke(VaultFile.Opener.passphrase(PASSPHRASE),
                Keyslot.PASSPHRASE))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("last way into");
    }

    /**
     * A passphrase change replaces one slot. Every device keeps working, which is the difference
     * between a keyslot and a key.
     */
    @Test
    void changing_the_passphrase_does_not_disturb_a_device(@TempDir final Path dir) {
        final VaultFile vault = vault(dir);
        final byte[] share = share(1);
        vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), share, "the laptop", "USER_SCOPED");

        vault.rekey(PASSPHRASE, "a different one".toCharArray());

        assertThat(vault.read(share)).as("the device never heard about it").containsKey(
                "github.token");
        assertThat(vault.read("a different one".toCharArray())).containsKey("github.token");
        assertThatThrownBy(() -> vault.read(PASSPHRASE)).isInstanceOf(VaultException.class);
    }

    @Test
    void the_same_share_is_not_enrolled_twice(@TempDir final Path dir) {
        final VaultFile vault = vault(dir);
        final byte[] share = share(1);
        vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), share, "the laptop", "USER_SCOPED");

        assertThatThrownBy(() -> vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), share,
                "the laptop again", "USER_SCOPED"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("already enrolled as 'the laptop'");
    }

    @Test
    void a_storage_kind_nobody_declared_is_refused(@TempDir final Path dir) {
        final VaultFile vault = vault(dir);

        assertThatThrownBy(() -> vault.enroll(VaultFile.Opener.passphrase(PASSPHRASE), share(1),
                "the laptop", "A_DRAWER"))
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("not a kind of storage");
    }

    private static int indexOf(final byte[] haystack, final byte[] needle) {
        outer:
        for (int at = 0; at + needle.length <= haystack.length; at++) {
            for (int inner = 0; inner < needle.length; inner++) {
                if (haystack[at + inner] != needle[inner]) {
                    continue outer;
                }
            }
            return at;
        }
        return -1;
    }
}
