package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Waiting}: what an agent declares waiting for a person looks like, how that declaration is
 * read and bounded, and what a screen then says.
 */
class WaitingTest {

    private static final String MINIMAL = """
            name: example
            binary: example-cli

            git_identity:
              name: Example
              email: noreply@example.com

            headless:
              prompt_flag: "-p"
            """;

    private static final Waiting TRUST = new Waiting(
            List.of(new Waiting.Rule("Trust this folder?", 3, "whether to trust the folder")),
            List.of(new Waiting.Rule("(END)", 1, null)), null);

    private static AgentDefinition read(String yaml) {
        return AgentDefinitionReader.read(new StringReader(yaml), "test.yaml");
    }

    // --- the declaration

    @Test
    void readsTheDeclarationFromTheAgentsOwnYamlAndCarriesItThroughItsDescription() {
        final AgentDefinition definition = read(MINIMAL + """
                session:
                  waiting:
                    screen:
                      - contains: "Trust this folder?"
                        in_last_lines: 3
                        for: "whether to trust the folder"
                    not_its_screen:
                      - contains: "(END)"
                        in_last_lines: 1
                    last_message:
                      record: { type: result, subtype: success }
                      text: result
                """);

        final Waiting expected = new Waiting(TRUST.screen(), TRUST.notItsScreen(),
                new Waiting.LastMessage(Map.of("type", "result", "subtype", "success"), "result"));
        assertThat(definition.waiting()).isEqualTo(expected);
        // What 'describe' and the socket carry comes back the same.
        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(definition)).waiting()).isEqualTo(expected);
    }

    @Test
    void anAgentThatDeclaresNothingHasNoDeclarationRatherThanAnEmptyOne() {
        assertThat(read(MINIMAL).waiting()).isNull();
        assertThatThrownBy(() -> read(MINIMAL + "session:\n  waiting: {}\n"))
                .isInstanceOf(AgentException.class).hasMessageContaining("leaving it out");
    }

    @Test
    void refusesAMisspeltKeyRatherThanReadingARuleThatNeverMatches() {
        assertThatThrownBy(() -> read(MINIMAL + """
                session:
                  waiting:
                    screen:
                      - contain: "Trust this folder?"
                """)).isInstanceOf(AgentException.class).hasMessageContaining("test.yaml").hasMessageContaining("'contain'");
        assertThatThrownBy(() -> read(MINIMAL + "session:\n  waiting:\n    screens: []\n"))
                .hasMessageContaining("'screens'");
    }

    @Test
    void refusesAScreenRuleOfMoreThanOneLineOrPastItsBounds() {
        assertThatThrownBy(() -> new Waiting.Rule("one\ntwo", null, null)).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new Waiting.Rule("x".repeat(Waiting.LONGEST_TEXT + 1), null, null))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new Waiting.Rule("ok", Waiting.MOST_LINES + 1, null)).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new Waiting.Rule("ok", 0, null)).isInstanceOf(AgentException.class);
    }

    @Test
    void aPathologicalDeclarationIsRefusedWhenReadNotTimedOutWhenMatched() {
        // Seventeen rules, each as long as allowed: past the bound, so refused at load - the cost of a
        // declaration is bounded by what it may say, never by how politely it is written.
        final StringBuilder yaml = new StringBuilder(MINIMAL + "session:\n  waiting:\n    screen:\n");
        for (int i = 0; i <= Waiting.MOST_RULES; i++) {
            yaml.append("      - contains: \"").append("a".repeat(Waiting.LONGEST_TEXT)).append("\"\n");
        }
        assertThatThrownBy(() -> read(yaml.toString())).isInstanceOf(AgentException.class)
                .hasMessageContaining("at most " + Waiting.MOST_RULES);

        // And the most a declaration may be, against the largest screen tmux draws, reads in no time.
        final List<Waiting.Rule> most = new java.util.ArrayList<>();
        for (int i = 0; i < Waiting.MOST_RULES; i++) {
            most.add(new Waiting.Rule("a".repeat(Waiting.LONGEST_TEXT - 1) + "b", null, null));
        }
        final Waiting heaviest = new Waiting(most, most, null);
        final String screen = ("a".repeat(500) + "\n").repeat(500);
        final long started = System.nanoTime();
        assertThat(heaviest.read(screen).seen()).isEqualTo(Waiting.Seen.NOT_WAITING);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void notItsScreenMeansSomethingOnlyBesideScreenRules() {
        assertThatThrownBy(() -> new Waiting(List.of(), List.of(new Waiting.Rule("(END)", 1, null)), null))
                .isInstanceOf(AgentException.class).hasMessageContaining("not_its_screen");
    }

    // --- the screen

    @Test
    void aDeclaredQuestionInItsRegionIsWaitingAndSaysWhatFor() {
        final Waiting.Reading reading = TRUST.read("""
                stub: starting
                Trust this folder? [y/N]
                """);

        assertThat(reading.seen()).isEqualTo(Waiting.Seen.WAITING);
        assertThat(reading.waitingFor()).isEqualTo("whether to trust the folder");
    }

    @Test
    void theSameTextAboveItsRegionIsNotWaiting() {
        // The agent quoting its own question back, further up the screen, is not a question to anybody.
        assertThat(TRUST.read("""
                Trust this folder? [y/N] y
                one
                two
                three
                stub: at work, nothing asked
                """).seen()).isEqualTo(Waiting.Seen.NOT_WAITING);
    }

    @Test
    void emptyLinesAndTrailingBlanksDoNotCountTowardsTheRegion() {
        // tmux pads the screen with blank lines and spaces below the cursor.
        assertThat(TRUST.read("Trust this folder? [y/N]   \n\n\n\n\n   \n").seen()).isEqualTo(Waiting.Seen.WAITING);
    }

    @Test
    void matchesWithoutRegardToCase() {
        assertThat(TRUST.read("TRUST THIS FOLDER? [Y/n]").seen()).isEqualTo(Waiting.Seen.WAITING);
    }

    @Test
    void aScreenThatIsNotTheAgentsSaysNothingAboutItEvenOverAQuestion() {
        // A pager open over the agent's question: reporting it as waiting - or as idle - would be reporting
        // on the pager.
        assertThat(TRUST.read("Trust this folder? [y/N]\n(END)\n").seen()).isEqualTo(Waiting.Seen.NOT_ITS_SCREEN);
    }

    @Test
    void quietIsNotWaiting() {
        assertThat(TRUST.read("").seen()).isEqualTo(Waiting.Seen.NOT_WAITING);
        assertThat(TRUST.read("stub: at work, nothing asked\n").seen()).isEqualTo(Waiting.Seen.NOT_WAITING);
    }

    // --- the last message

    @Test
    void theLastMessageIsTheTextOfTheLastMatchingRecord() {
        final Waiting.LastMessage last = new Waiting.LastMessage(Map.of("type", "result"), "result");

        assertThat(last.of(List.of(
                Map.of("type", "system", "session_id", "s-1"),
                Map.of("type", "result", "result", "first run"),
                Map.of("type", "assistant", "result", "not a result record"),
                Map.of("type", "result", "result", "Which branch should I use?")))).isEqualTo("Which branch should I use?");
    }

    @Test
    void aRunWithNoMatchingRecordHasNoLastMessage() {
        final Waiting.LastMessage last = new Waiting.LastMessage(Map.of("type", "result"), "result");

        assertThat(last.of(List.of(Map.of("type", "system"), "not a record", Map.of("type", "result", "result", 3))))
                .isNull();
    }
}
