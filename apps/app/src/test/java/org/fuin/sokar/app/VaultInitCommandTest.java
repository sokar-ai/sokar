package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Test for {@link VaultInitCommand}.
 * <p>
 * Driven through {@code --passphrase-command}, because the typed path needs a console and a test
 * that faked one would be testing the fake. What is worth asserting either way is the same: a
 * vault comes into being, it opens with what was given, and an existing one is never replaced.
 */
class VaultInitCommandTest {

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            default -> null;
        }, dir);
        return new SokarContext(new ProcessCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private int run(final SokarContext context, final StringWriter out, final StringWriter err,
            final String... arguments) {
        // The class, not an instance: picocli only runs a command through the factory when it
        // creates it, and an instance made here would keep the real machine's context - which on
        // the first run of this test meant it looked at the developer's own vault.
        final CommandLine command =
                new CommandLine(VaultInitCommand.class, new SokarFactory(context));
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        return command.execute(arguments);
    }

    @Test
    void creates_an_empty_vault_that_opens_with_what_was_given(@TempDir final Path dir) {
        final SokarContext context = context(dir);
        final StringWriter out = new StringWriter();

        final int code = run(context, out, new StringWriter(),
                "--passphrase-command", "printf hunter2");

        assertThat(code).isZero();
        assertThat(context.vault().exists()).isTrue();
        assertThat(context.vault().read("hunter2".toCharArray())).isEmpty();
        assertThat(out.toString()).contains("created").contains("holds nothing yet");
    }

    @Test
    void the_new_vault_has_the_passphrase_as_its_only_way_in(@TempDir final Path dir) {
        final SokarContext context = context(dir);

        run(context, new StringWriter(), new StringWriter(),
                "--passphrase-command", "printf hunter2");

        assertThat(context.vault().slots()).singleElement()
                .satisfies(slot -> assertThat(slot.recovery()).isTrue());
    }

    /**
     * The failure this command exists to prevent runs the other way too: a second init over a
     * vault holding credentials would replace a file nobody can get back.
     */
    @Test
    void never_replaces_a_vault_that_is_already_there(@TempDir final Path dir) {
        final SokarContext context = context(dir);
        run(context, new StringWriter(), new StringWriter(),
                "--passphrase-command", "printf hunter2");
        final StringWriter err = new StringWriter();

        final int code = run(context, new StringWriter(), err, "--passphrase-command",
                "printf somethingelse");

        assertThat(code).isEqualTo(70);
        assertThat(err.toString()).contains("already a vault");
        assertThat(context.vault().read("hunter2".toCharArray()))
                .as("the first one is untouched").isEmpty();
    }

    @Test
    void refuses_an_empty_passphrase(@TempDir final Path dir) {
        final SokarContext context = context(dir);
        final StringWriter err = new StringWriter();

        final int code = run(context, new StringWriter(), err, "--passphrase-command", "true");

        assertThat(code).isEqualTo(70);
        assertThat(context.vault().exists()).as("nothing was created").isFalse();
        assertThat(err.toString()).contains("produced nothing");
    }
}
