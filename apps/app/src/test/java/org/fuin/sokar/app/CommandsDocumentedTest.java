package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Every command the CLI has is on the page that lists them.
 * <p>
 * A command group with no section is a group nobody finds without guessing its name - measured: the
 * talk, credentials and providers groups had none, and a check that asked only for three task
 * subcommands had passed over them. Walked from the CLI's own model, so a command added later is
 * missing from the page the day it is added.
 */
@Tag("documents")
class CommandsDocumentedTest {

    private static final Path PAGE = Path.of("..", "..", "doc", "commands.md");

    @Test
    void everyGroupHasASectionAndEveryCommandIsNamedOnThePage() throws IOException {
        final String page = Files.readString(PAGE, StandardCharsets.UTF_8);
        final List<String> missing = new ArrayList<>();
        for (final CommandLine group : SokarCli.commandLine(SokarContext.real()).getSubcommands().values()) {
            final String name = group.getCommandName();
            if (group.getCommandSpec().usageMessage().hidden()) {
                continue;
            }
            // 'projects' is the plural spelling of 'project', and the page documents the one.
            final String documented = "projects".equals(name) ? "project" : name;
            if (!page.contains("\n## " + documented + "\n")) {
                missing.add("## " + documented);
            }
            for (final CommandLine command : group.getSubcommands().values()) {
                if (!command.getCommandSpec().usageMessage().hidden()
                        && !page.contains("sokar " + documented + " " + command.getCommandName())) {
                    missing.add("sokar " + documented + " " + command.getCommandName());
                }
            }
        }
        assertThat(missing).as("commands the CLI has and %s does not name", PAGE).isEmpty();
    }
}
