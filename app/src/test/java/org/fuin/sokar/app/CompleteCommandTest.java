package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * What TAB is answered with.
 * <p>
 * Driven through the real command tree rather than a fixture: the point of asking the program is
 * that the answers cannot drift from what the program accepts, and a test that built its own tree
 * would be testing the fixture.
 */
class CompleteCommandTest {

    private final CompleteCommand complete = new CompleteCommand();

    private final CommandLine root = SokarCli.commandLine(SokarContext.real());

    private List<String> completing(String... words) {
        return complete.candidatesFor(root, List.of(words));
    }

    @Test
    void theTopLevelOffersItsSubcommands() {
        assertThat(completing("sokar", "")).contains("task", "vault", "gate", "project");
    }

    @Test
    void aPartialWordNarrowsIt() {
        assertThat(completing("sokar", "va")).containsExactly("vault");
    }

    @Test
    void anAliasIsNotOfferedBesideTheNameItIsAnAliasFor() {

        // picocli keys aliases into the same subcommand map, so taking the keys would offer
        // 'task' and 'tasks' as if they were two commands to choose between.
        assertThat(completing("sokar", "")).contains("task").doesNotContain("tasks");
        assertThat(completing("sokar", "")).contains("project").doesNotContain("projects");
    }

    @Test
    void aHiddenCommandIsNeverOffered() {

        // __complete is how this works and is not something to type. A completion that offered it
        // would advertise the machinery instead of the product.
        assertThat(completing("sokar", "")).doesNotContain("__complete");
        assertThat(completing("sokar", "__")).isEmpty();
    }

    @Test
    void aSubcommandOffersItsOwnVerbs() {
        assertThat(completing("sokar", "task", "")).contains("start", "stop", "remove", "attach");
        assertThat(completing("sokar", "project", "")).contains("list", "delete");
    }

    @Test
    void aDashOffersTheLongOptionsOfTheCommandItIsIn() {

        // Of that command, not of the root: 'sokar task remove --' must not offer the top-level
        // options, or every list is the same list.
        assertThat(completing("sokar", "task", "remove", "--")).contains("--force", "--rescue");
    }

    @Test
    void shortOptionsAreLeftOut() {

        // Offering '-p' beside '--project-file' doubles the list to say the same thing twice.
        assertThat(completing("sokar", "task", "start", "-")).allMatch(c -> c.startsWith("--"));
    }

    @Test
    void anOptionAlreadyTypedDoesNotMoveUsOutOfTheCommand() {

        // The words between the program and the partial word say which command we are in, and a
        // word that is not a subcommand is an option or an argument rather than a step down.
        assertThat(completing("sokar", "task", "remove", "--force", "--"))
                .contains("--rescue");
    }

    @Test
    void aShellThatHasNoScriptCompletesToNothingRatherThanToEverything() {
        assertThat(completing("sokar", "completion", "")).containsExactly("bash", "zsh");
        assertThat(completing("sokar", "completion", "f")).isEmpty();
    }

    @Test
    void nothingTypedAtAllIsAnswerableRatherThanAFailure() {
        assertThat(complete.candidatesFor(root, List.of())).isEmpty();
        assertThat(complete.candidatesFor(null, List.of("sokar", ""))).isEmpty();
    }
}
