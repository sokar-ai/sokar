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
}
