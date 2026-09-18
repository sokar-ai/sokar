package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link Keyslots}, which is what an interface sees of B60.
 */
class KeyslotsTest {

    private static final char[] PASSPHRASE = "correct horse".toCharArray();

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private String share(final int seed) {
        final byte[] share = new byte[Keyslots.SHARE_BYTES];
        new java.util.Random(seed).nextBytes(share);
        return Base64.getEncoder().encodeToString(share);
    }

    private SokarContext withVault(final Path dir) {
        final SokarContext context = context(dir);
        context.vault().write(Map.of("github.token", VaultEntry.of("ghp_example")), PASSPHRASE);
        return context;
    }

    @Test
    void a_machine_without_a_vault_says_so_rather_than_failing(@TempDir final Path dir) {
        final Keyslots keyslots = new Keyslots(context(dir));

        assertThat(keyslots.list()).isEmpty();
        assertThat(keyslots.enroll("the laptop", share(1), "USER_SCOPED").outcome())
                .isEqualTo(Keyslots.Enrolled.VAULT_WITHOUT_KEYSLOTS);
        assertThat(keyslots.unlock(share(1), null).outcome())
                .isEqualTo(Keyslots.Unlocked.VAULT_WITHOUT_KEYSLOTS);
        assertThat(keyslots.revoke("whatever").outcome())
                .isEqualTo(Keyslots.Revoked.VAULT_WITHOUT_KEYSLOTS);
    }

    @Test
    void a_new_vault_lists_the_passphrase_and_nothing_else(@TempDir final Path dir) {
        assertThat(new Keyslots(withVault(dir)).list()).singleElement()
                .satisfies(slot -> assertThat(slot.recovery()).isTrue());
    }

    /**
     * A daemon has no terminal, so a locked vault is answered rather than waited on. Enrolling
     * needs the master key, and the master key needs something that already opens the vault.
     */
    @Test
    void enrolling_into_a_locked_vault_is_refused_with_a_reason(@TempDir final Path dir) {
        final Keyslots keyslots = new Keyslots(withVault(dir));

        final Keyslots.Enrollment enrolled =
                keyslots.enroll("the laptop", share(1), "USER_SCOPED");

        assertThat(enrolled.outcome()).isEqualTo(Keyslots.Enrolled.VAULT_LOCKED);
        assertThat(enrolled.detail()).contains("nothing here can open the vault");
    }

    @Test
    void a_share_that_is_not_thirty_two_bytes_is_refused(@TempDir final Path dir) {
        final Keyslots keyslots = new Keyslots(withVault(dir));

        assertThat(keyslots.enroll("the laptop", "not base64 at all", "USER_SCOPED").outcome())
                .isEqualTo(Keyslots.Enrolled.BAD_SHARE);
        assertThat(keyslots.enroll("the laptop",
                Base64.getEncoder().encodeToString(new byte[8]), "USER_SCOPED").outcome())
                .isEqualTo(Keyslots.Enrolled.BAD_SHARE);
        assertThat(keyslots.unlock("also not base64", null).outcome())
                .isEqualTo(Keyslots.Unlocked.BAD_SHARE);
    }

    @Test
    void a_share_no_slot_takes_is_refused_and_holds_nothing(@TempDir final Path dir) {
        final Keyslots keyslots = new Keyslots(withVault(dir));

        final Keyslots.Unlock unlocked = keyslots.unlock(share(9), null);

        assertThat(unlocked.outcome()).isEqualTo(Keyslots.Unlocked.SHARE_REJECTED);
        assertThat(unlocked.until()).as("nothing is open, so nothing runs out").isEmpty();
    }
}
