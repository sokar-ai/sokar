package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests that {@code vault unlock} with no vault offers {@code vault init}, and {@code vault import} with nothing to
 * import offers {@code vault login}, each run as the command itself on this terminal.
 */
class VaultOfferTest {

    @TempDir
    private Path dir;

    private final List<List<String>> ran = new ArrayList<>();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), command -> {
            ran.add(command);
            return 0;
        });
    }

    private int run(final Object command, final String... arguments) {
        final CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(arguments);
    }

    @Test
    void unlockWithNoVaultOffersInitAtATerminal() {
        final VaultUnlockCommand unlock = new VaultUnlockCommand();
        unlock.setContext(context());
        unlock.asker = question -> "";

        run(unlock);

        assertThat(ran).singleElement().satisfies(command -> assertThat(command.subList(1, command.size()))
                .containsExactly("vault", "init"));
    }

    @Test
    void unlockWithNoVaultAndNobodyToAskRefusesAsBefore() {
        final VaultUnlockCommand unlock = new VaultUnlockCommand();
        unlock.setContext(context());

        assertThat(run(unlock)).isEqualTo(1);
        assertThat(ran).isEmpty();
        assertThat(err.toString()).contains("sokar vault init");
    }

    @Test
    void importWithNothingToImportOffersTheAgentsLogin() {
        assertThat(VaultImportCommand.loginInstead("claude", new Offer(question -> "", false, false,
                new PrintWriter(err, true)), context())).isTrue();
        assertThat(ran).singleElement().satisfies(command -> assertThat(command.subList(1, command.size()))
                .containsExactly("vault", "login", "claude"));
        assertThat(VaultImportCommand.loginInstead("claude", new Offer(Offer.Asker.NOBODY, false, false,
                new PrintWriter(err, true)), context())).isFalse();
    }
}
