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
}
