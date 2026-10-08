package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link VaultRemoveCommand}.
 */
class VaultRemoveCommandTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = SokarCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    @Test
    void saysSoWhenThereIsNoVault(@TempDir Path dir) {
        assertThat(execute(context(dir), "vault", "remove", "anything")).isEqualTo(69);
        assertThat(err.toString()).contains("no vault at");
    }

    @Test
    void removesOnlyTheNamedEntry(@TempDir Path dir) {
        final var vault = context(dir).vault();
        vault.write(Map.of("keep", VaultEntry.of("one"), "drop", VaultEntry.of("two")), PASSPHRASE);

        vault.update(PASSPHRASE, entries -> {
            entries.remove("drop");
            return entries;
        });

        assertThat(vault.read(PASSPHRASE)).containsOnlyKeys("keep");
    }

    @Test
    void removingSomethingAbsentIsNotAFailure(@TempDir Path dir) {

        // The wanted state is "not in the vault", and it already holds. Failing here would make
        // a cleanup script stop on its second run.
        final var vault = context(dir).vault();
        vault.write(Map.of("keep", VaultEntry.of("one")), PASSPHRASE);

        vault.update(PASSPHRASE, entries -> {
            assertThat(entries.remove("absent")).isNull();
            return entries;
        });

        assertThat(vault.read(PASSPHRASE)).containsOnlyKeys("keep");
    }

    @Test
    void aGrantWhoseServiceCannotBeToldSaysWhy() {
        assertThat(VaultRemoveCommand.revoke(null, "rt-1")).contains("cannot be told");
        assertThat(VaultRemoveCommand.revoke(new VaultEntry("-", "oauth-device", Map.of("client_id", "x",
                "device_authorization_url", "https://a.invalid/d", "token_url", "https://a.invalid/t")), "rt-1"))
                .contains("revocation_url").doesNotContain("rt-1");
    }

    @Test
    void aTokenThatNeverExpiresIsKeptUntilThePersonSaysTheyKnowItLivesOnAndThenSaysWhereToRevokeIt(@TempDir Path dir) {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.fuin.sokar.vault.KernelKeyring.available(),
                "libkeyutils is not installed");
        final SokarContext context = context(dir);
        final org.fuin.sokar.vault.KernelKeyring keyring =
                new org.fuin.sokar.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            context.vault().write(Map.of(
                    "copilot", new VaultEntry("-", "oauth-device", Map.of("client_id", "Ov23li",
                            "device_authorization_url", "https://github.com/login/device/code",
                            "token_url", "https://github.com/login/oauth/access_token")),
                    TaskSecrets.GRANT_PREFIX + "copilot", new VaultEntry("gho_1", VaultAuthorizeCommand.HELD, Map.of())),
                    PASSPHRASE);
            keyring.store(PASSPHRASE);

            // Only the application's owner can revoke it at GitHub; forgetting it here would leave it working there.
            assertThat(execute(context, "vault", "remove", "copilot")).isEqualTo(1);
            assertThat(err.toString()).contains("only the application's owner can")
                    .contains("Settings → Applications → Authorized OAuth Apps").doesNotContain("gho_1");
            assertThat(context.vault().read(PASSPHRASE)).containsKey(TaskSecrets.GRANT_PREFIX + "copilot");

            assertThat(execute(context, "vault", "remove", "copilot", "--without-revoking")).isZero();
            assertThat(out.toString()).contains("works there until it is revoked on GitHub");
            assertThat(context.vault().read(PASSPHRASE)).isEmpty();
        } finally {
            keyring.forget();
        }
    }
}
