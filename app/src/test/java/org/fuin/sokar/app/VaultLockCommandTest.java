package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.KernelKeyring;
import org.fuin.sokar.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link VaultLockCommand} and the unlock it undoes, against the real kernel keyring.
 * <p>
 * The keyring description is derived from the vault's path, and the vault is in a temporary
 * directory, so nothing here can touch the passphrase an operator has cached for their own vault.
 */
class VaultLockCommandTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private KernelKeyring keyring(SokarContext context) {
        return new KernelKeyring(context.paths().vaultKeyringKey());
    }

    @Test
    void unlockingCachesThePassphraseAndLockingDropsIt(@TempDir Path dir) {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        final SokarContext context = context(dir);
        context.vault().write(Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")), PASSPHRASE);
        try {
            assertThat(execute(context, "vault", "unlock",
                    "--passphrase-command", "printf 'correct horse battery staple\\n'"))
                    .isEqualTo(0);
            assertThat(keyring(context).read()).isPresent();

            assertThat(execute(context, "vault", "lock")).isEqualTo(0);

            // Nothing restarted, and the next command is back to asking.
            assertThat(keyring(context).read()).isEmpty();
            assertThat(out.toString()).contains("locked");
        } finally {
            keyring(context).forget();
        }
    }

    @Test
    void refusesAPassphraseThatDoesNotOpenTheVault(@TempDir Path dir) {

        // Caching an unverified passphrase moves the failure to the next command, where a wrong
        // one reads as a corrupt store rather than as a typo.
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        final SokarContext context = context(dir);
        context.vault().write(Map.of("anthropic", VaultEntry.of("sk-not-a-real-key")), PASSPHRASE);
        try {
            assertThat(execute(context, "vault", "unlock",
                    "--passphrase-command", "printf 'wrong\\n'")).isEqualTo(70);

            assertThat(err.toString()).contains("does not open").contains("nothing was cached");
            assertThat(keyring(context).read()).isEmpty();
        } finally {
            keyring(context).forget();
        }
    }

    @Test
    void lockingWhenNothingIsCachedIsNotAFailure(@TempDir Path dir) {

        // The wanted state is "not cached", and it already holds; failing would stop a script
        // that locks on its way out.
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        assertThat(execute(context(dir), "vault", "lock")).isEqualTo(0);
        assertThat(out.toString()).contains("nothing was cached");
    }

    @Test
    void refusesToUnlockAVaultThatDoesNotExistAndNamesInit(@TempDir Path dir) {

        // It used to cache the passphrase and exit 0, and the first 'vault put' then made the vault
        // with it - typed once, with nothing to compare a typo against. Measured by Agent Frontend.
        final SokarContext context = context(dir);
        try {
            assertThat(execute(context, "vault", "unlock",
                    "--passphrase-command", "printf 'correct horse battery staple\\n'"))
                    .isEqualTo(1);
            assertThat(err.toString()).contains("there is no vault at").contains("sokar vault init")
                    .contains("nothing was cached");
            if (KernelKeyring.available()) {
                assertThat(keyring(context).read()).isEmpty();
            }
        } finally {
            if (KernelKeyring.available()) {
                keyring(context).forget();
            }
        }
    }

    @Test
    void saysWhatLockingDoesNotReach(@TempDir Path dir) {

        // A running task's proxy read its credential when it started and holds it in its own
        // memory. Locking cannot reach that, and an operator who believes otherwise has locked
        // nothing they think they locked.
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        assertThat(execute(context(dir), "vault", "lock")).isEqualTo(0);
        assertThat(out.toString()).contains("1 task still hold");
    }

    @Test
    void theOlderFlagIsTheSameOperation(@TempDir Path dir) {

        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\n");

        assertThat(execute(context(dir), "vault", "unlock", "--forget")).isEqualTo(0);
        assertThat(out.toString()).contains("nothing was cached").contains("1 task still hold");
    }
}
