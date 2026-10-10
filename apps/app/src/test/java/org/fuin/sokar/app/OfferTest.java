package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Offer}: where Sokar would name a command to type, it offers that command's own remedy at a terminal,
 * checks again after it, and refuses as before where nobody can answer.
 */
class OfferTest {

    private final StringWriter err = new StringWriter();

    private final List<String> asked = new ArrayList<>();

    private boolean fixed;

    private Offer.Remedy hooks(final boolean destructive) {
        return new Offer.Remedy("the hooks are not registered with podman", "Register them now, as 'sokar setup' does?",
                "sokar setup", destructive ? "the hook files in ~/.config/containers" : "", destructive,
                () -> {
                    fixed = true;
                    return true;
                }, () -> fixed);
    }

    private Offer offer(final Offer.Asker asker, final boolean noInput, final boolean yes) {
        return new Offer(question -> {
            asked.add(question);
            return asker.ask(question);
        }, noInput, yes, new PrintWriter(err, true));
    }

    @Test
    void atATerminalTheRemedyIsOfferedRunAndCheckedAgain() {
        assertThat(offer(question -> "", false, false).resolve(hooks(false))).isTrue();
        assertThat(fixed).isTrue();
        assertThat(asked).singleElement().asString().contains("Register them now").endsWith("[Y/n]");
    }

    @Test
    void declinedOrWithoutATerminalItRefusesAsBeforeNamingTheCommand() {
        assertThat(offer(question -> "n", false, false).resolve(hooks(false))).isFalse();
        assertThat(offer(question -> null, false, false).resolve(hooks(false))).isFalse();
        assertThat(fixed).isFalse();
        assertThat(err.toString()).contains("the hooks are not registered with podman").contains("'sokar setup'");
    }

    @Test
    void noInputAsksNothingAndYesTakesOnlyAHarmlessRemedy() {
        assertThat(offer(question -> "y", true, false).resolve(hooks(false))).as("--no-input").isFalse();
        assertThat(asked).isEmpty();
        assertThat(offer(question -> null, false, true).resolve(hooks(false))).as("--yes, harmless").isTrue();
        assertThat(asked).as("taken without asking").isEmpty();
    }

    @Test
    void aDestructiveRemedyShowsWhatItTouchesAndDefaultsToNoEvenWithYes() {
        assertThat(offer(question -> "", false, true).resolve(hooks(true))).as("Enter alone is no").isFalse();
        assertThat(asked).singleElement().asString().endsWith("[y/N]");
        assertThat(err.toString()).contains("the hook files in ~/.config/containers");
        assertThat(offer(question -> "y", false, false).resolve(hooks(true))).isTrue();
    }

    @Test
    void aRemedyThatDidNotHelpIsSaidAndRefused() {
        final Offer.Remedy useless = new Offer.Remedy("no vault here", "Make one now?", "sokar vault init", "", false,
                () -> true, () -> false);

        assertThat(offer(question -> "y", false, false).resolve(useless)).isFalse();
        assertThat(err.toString()).contains("still").contains("'sokar vault init'");
    }

    @Test
    void whatHoldsAlreadyAsksNothing() {
        fixed = true;
        assertThat(offer(question -> "n", false, false).resolve(hooks(false))).isTrue();
        assertThat(asked).isEmpty();
    }

    @Test
    void aChoiceIsAskedByNumberAndRefusedWhereNobodyAnswers() {
        final List<String> agents = List.of("claude", "omp", "pi");

        assertThat(offer(question -> "2", false, false).choose("several agents are installed", agents,
                "sokar task start --agent <name>")).isEqualTo("omp");
        assertThat(asked).singleElement().asString().contains("[1-3]");
        assertThat(err.toString()).contains("1  claude").contains("3  pi");

        assertThat(offer(question -> "7", false, false).choose("several agents are installed", agents,
                "sokar task start --agent <name>")).as("no such number").isNull();
        assertThat(offer(question -> null, false, true).choose("several agents are installed", agents,
                "sokar task start --agent <name>")).as("nobody, even with --yes").isNull();
        assertThat(err.toString()).contains("'sokar task start --agent <name>'");
    }

    @Test
    void theAgentChosenOnceIsTheOneForTheWholeStart() {
        final int[] askedTimes = {0};
        final Offer once = new Offer(question -> {
            askedTimes[0]++;
            return "1";
        }, false, false, new PrintWriter(err, true));
        final String[] chosen = {null};

        assertThat(TaskLaunch.agentName(List.of("claude", "pi"), null, once, chosen)).isEqualTo("claude");
        assertThat(TaskLaunch.agentName(List.of("claude", "pi"), null, once, chosen)).isEqualTo("claude");
        assertThat(askedTimes[0]).isEqualTo(1);
        assertThat(TaskLaunch.agentName(List.of("claude", "pi"), "pi", once, new String[1])).as("named").isEqualTo("pi");
        assertThat(TaskLaunch.agentName(List.of("claude"), null, once, new String[1])).as("only one").isNull();
    }
}
