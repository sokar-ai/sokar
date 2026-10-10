package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for the one command builder that serves every agent.
 */
class HeadlessCommandTest {

    private static final String FULL = """
            name: example
            binary: example-cli

            git_identity:
              name: Example
              email: noreply@example.com

            headless:
              prompt_flag: "-p"
              model_flag: "--model"
              max_turns_flag: "--max-turns"
              verbose_flag: "--verbose"
              output_format_flags: ["--output-format", "stream-json"]

            session:
              supports_resume: true
              resume_flag: "--resume"
            """;

    private static Agent agent(String yaml) {
        final AgentDefinition definition = AgentDefinitionReader.read(new StringReader(yaml), "t.yaml");
        return () -> definition;
    }

    @Test
    void buildsThePlainCase() {

        assertThat(agent(FULL).headlessCommand(RunRequest.of("do the thing")))
                .containsExactly("example-cli", "-p", "do the thing");
    }

    @Test
    void turnsTheAgentsOwnPermissionPromptsOffWithoutBeingAsked() {

        // No flag, no request field, no condition: an agent inside a task is unrestricted by
        // construction, and one that stops to ask whether it may run a command is asking about a
        // restriction the container already imposes. Unattended there is nobody to answer.
        final var command = agent(FULL + """

                sandboxed:
                  arguments: ["--dangerously-skip-permissions"]
                """).headlessCommand(RunRequest.of("do the thing"));

        // Directly after the binary, so it cannot land after a positional prompt.
        assertThat(command)
                .containsExactly("example-cli", "--dangerously-skip-permissions", "-p",
                        "do the thing");
    }

    @Test
    void startsAnAttachedAgentTheSameWayAsAnUnattendedOne() {

        // The attached path takes this list and the unattended one builds on it, so the flags
        // cannot differ between them. Reported the other way round: an attached claude asked to
        // approve a command, while the same run unattended would have hung on the question.
        final AgentDefinition definition = agent(FULL + """

                sandboxed:
                  arguments: ["--dangerously-skip-permissions"]
                """).definition();

        assertThat(definition.sandboxedCommand())
                .containsExactly("example-cli", "--dangerously-skip-permissions");
    }

    @Test
    void startsAnAttachedAgentOnTheModelThatWasAskedFor() {

        // Measured on 2026-09-29: an attended start accepted --model and dropped it, and the agents'
        // headers named their own defaults.
        assertThat(agent(FULL).definition().attendedCommand("cheap-model"))
                .containsExactly("example-cli", "--model", "cheap-model");
        assertThat(agent(FULL).definition().attendedCommand(null)).containsExactly("example-cli");
    }

    @Test
    void refusesAModelForAnAttachedAgentThatTakesNone() {
        final String noModel = FULL.replace("  model_flag: \"--model\"\n", "");

        assertThatThrownBy(() -> agent(noModel).definition().attendedCommand("cheap-model"))
                .isInstanceOf(AgentException.class).hasMessageContaining("does not take a model");
    }

    @Test
    void startsAnAgentWithNothingToTurnOffAsItself() {
        assertThat(agent(FULL).definition().sandboxedCommand()).containsExactly("example-cli");
    }

    @Test
    void addsNothingForAnAgentThatHasNoPromptsToTurnOff() {

        // The absent section is the common case and must not become an empty argument.
        assertThat(agent(FULL).headlessCommand(RunRequest.of("do the thing")))
                .containsExactly("example-cli", "-p", "do the thing");
    }

    @Test
    void takesThePromptPositionallyWhenThereIsNoFlagForIt() {

        // 'pi --print --mode json "<prompt>"'. An empty flag is a real shape, not a missing value:
        // adding it anyway would pass an empty argument the agent then has to interpret.
        final var command = agent("""
                name: pi
                binary: pi

                git_identity:
                  name: Pi
                  email: noreply@pi.dev

                headless:
                  prompt_flag: ""
                  output_format_flags: ["--print", "--mode", "json"]
                """).headlessCommand(new RunRequest("go", null, null, null, false, true));

        assertThat(command).containsExactly("pi", "--print", "--mode", "json", "go");
        assertThat(command).doesNotContain("");
    }

    @Test
    void putsThePromptLast() {

        // An agent taking the prompt positionally needs it after the flags; one taking it behind a
        // flag does not care. Last is the only ordering that works for both.
        final var command = agent(FULL).headlessCommand(
                new RunRequest("go", "big-model", Integer.valueOf(5), null, true, true));

        assertThat(command).endsWith("-p", "go");
        assertThat(command).containsSequence("--model", "big-model");
        assertThat(command).containsSequence("--max-turns", "5");
        assertThat(command).contains("--verbose", "--output-format", "stream-json");
    }

    @Test
    void resumesASession() {

        assertThat(agent(FULL).headlessCommand(
                new RunRequest("go", null, null, "abc-123", false, false)))
                .containsExactly("example-cli", "--resume", "abc-123", "-p", "go");
    }

    @Test
    void refusesToAskForSomethingTheAgentCannotDo() {

        // Silently dropping the flag would run the agent with a different model than the operator
        // asked for, and report success.
        final Agent minimal = agent("""
                name: example
                binary: example-cli
                git_identity:
                  name: Example
                  email: noreply@example.com
                headless:
                  prompt_flag: "-p"
                """);

        assertThatThrownBy(() -> minimal.headlessCommand(
                new RunRequest("go", "big-model", null, null, false, false)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does not take a model");

        assertThatThrownBy(() -> minimal.headlessCommand(
                new RunRequest("go", null, null, "abc", false, false)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("cannot resume");
    }

    @Test
    void omitsFlagsThatWereNotAskedFor() {

        assertThat(agent(FULL).headlessCommand(RunRequest.of("go")))
                .doesNotContain("--verbose", "--output-format", "--model");
    }

    @Test
    void anAgentInATaskWithAMailboxIsToldWhereItsInstructionsAre() {

        // An agent was never told that its mailbox existed (2026-10-04): its own way of taking standing
        // instructions gets the file, directly behind what turns its prompts off, attended and unattended alike.
        final AgentDefinition definition = agent(FULL + """

                sandboxed:
                  arguments: ["--dangerously-skip-permissions"]
                instructions:
                  arguments: ["--append-system-prompt-file", "{file}"]
                """).definition();

        assertThat(definition.instructed(definition.attendedCommand(null), "/run/sokar/mail/README.md"))
                .containsExactly("example-cli", "--dangerously-skip-permissions", "--append-system-prompt-file",
                        "/run/sokar/mail/README.md");
        assertThat(definition.instructed(agent(FULL + """

                sandboxed:
                  arguments: ["--dangerously-skip-permissions"]
                instructions:
                  arguments: ["--append-system-prompt-file", "{file}"]
                """).headlessCommand(RunRequest.of("do the thing")), "/run/sokar/mail/README.md"))
                .as("before a positional prompt").containsExactly("example-cli", "--dangerously-skip-permissions",
                        "--append-system-prompt-file", "/run/sokar/mail/README.md", "-p", "do the thing");
    }

    @Test
    void anAgentThatDeclaresNoWayToTakeInstructionsIsStartedAsBefore() {
        final AgentDefinition definition = agent(FULL).definition();

        assertThat(definition.instructed(definition.attendedCommand(null), "/run/sokar/mail/README.md"))
                .containsExactly("example-cli");
    }

    @Test
    void theDeclarationSurvivesTheWayToSokar() {
        final AgentDefinition definition = agent(FULL + """

                instructions:
                  arguments: ["--append-system-prompt", "{file}"]
                """).definition();

        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(definition)).instructionArguments())
                .containsExactly("--append-system-prompt", "{file}");
    }

    @Test
    void anAgentSaysWhatItsScreenShowsAtRestAndItSurvivesTheWayToSokar() {

        // So a message that names it can wake it, and never while it works or asks a person something (
        // 2026-10-04): what must be on the screen, and what must not.
        final AgentDefinition definition = agent(FULL.replace("  resume_flag: \"--resume\"\n",
                "  resume_flag: \"--resume\"\n  at_rest:\n    shows: [\"bypass permissions on\"]\n"
                        + "    lacks: [\"esc to interrupt\", \"Esc to cancel\"]\n")).definition();

        assertThat(definition.atRest()).isNotNull();
        assertThat(definition.atRest().matches("> \n  bypass permissions on (shift+tab)")).isTrue();
        assertThat(definition.atRest().matches("* Thinking (esc to interrupt)\n  bypass permissions on")).isFalse();
        assertThat(definition.atRest().matches("> ")).as("what it must show is missing").isFalse();
        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(definition)).atRest()).isEqualTo(definition.atRest());
        assertThat(agent(FULL).definition().atRest()).as("an agent that says nothing is never woken").isNull();
    }
}
