package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SessionIds}: where an agent declares it names its sessions, and how that is read.
 */
class SessionIdsTest {

    private static final String MINIMAL = """
            name: example
            binary: example-cli

            git_identity:
              name: Example
              email: noreply@example.com

            headless:
              prompt_flag: "-p"
            """;

    private static AgentDefinition read(String yaml) {
        return AgentDefinitionReader.read(new StringReader(yaml), "test.yaml");
    }

    @Test
    void readsBothWaysOfFindingTheIdAndCarriesThemThroughItsDescription() {
        final AgentDefinition definition = read(MINIMAL + """
                session:
                  supports_resume: true
                  resume_flag: "--resume"
                  session_id:
                    record: { type: system, subtype: init }
                    key: session_id
                    directory: ".example/projects"
                    suffix: ".jsonl"
                """);

        final SessionIds expected = new SessionIds(Map.of("type", "system", "subtype", "init"), "session_id",
                ".example/projects", ".jsonl");
        assertThat(definition.sessionIds()).isEqualTo(expected);
        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(definition)).sessionIds()).isEqualTo(expected);
    }

    @Test
    void anIdNothingCanContinueIsRefused() {
        assertThatThrownBy(() -> read(MINIMAL + """
                session:
                  session_id: { record: { type: system }, key: session_id }
                """)).isInstanceOf(AgentException.class).hasMessageContaining("does not support resume");
    }

    @Test
    void eachWayIsDeclaredWholeOrNotAtAll() {
        assertThatThrownBy(() -> new SessionIds(Map.of("type", "system"), null, null, null))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new SessionIds(Map.of(), null, ".example", null)).isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new SessionIds(Map.of(), null, null, null)).hasMessageContaining("leaving it out");
    }

    @Test
    void aDirectoryIsAPlainPathUnderTheHomeBecauseItReachesACommand() {
        for (final String bad : new String[] {"/etc", "../x", "a b", "$(id)", "a;b", ""}) {
            assertThatThrownBy(() -> new SessionIds(Map.of(), null, bad, ".jsonl")).as("'%s'", bad)
                    .isInstanceOf(AgentException.class);
        }
        assertThatThrownBy(() -> new SessionIds(Map.of(), null, ".x", "*.jsonl")).isInstanceOf(AgentException.class);
    }

    @Test
    void refusesAMisspeltKey() {
        assertThatThrownBy(() -> read(MINIMAL + """
                session:
                  supports_resume: true
                  resume_flag: "--resume"
                  session_id: { recrd: { type: system }, key: session_id }
                """)).hasMessageContaining("'recrd'");
    }

    @Test
    void theDeclaredKeyNotAnotherIsWhatIsPickedUp() {
        final List<Object> records = List.of(
                Map.of("type", "assistant", "session_id", "not-this"),
                Map.of("type", "system", "session_id", "s-1", "other_id", "o-1"),
                Map.of("type", "system", "session_id", "s-2"));

        assertThat(new SessionIds(Map.of("type", "system"), "session_id", null, null).of(records)).isEqualTo("s-1");
        // Changing the declared key, and nothing else, changes which field is picked up.
        assertThat(new SessionIds(Map.of("type", "system"), "other_id", null, null).of(records)).isEqualTo("o-1");
    }

    @Test
    void somethingThatCannotBeOneWordOnACommandLineIsNoId() {
        final SessionIds ids = new SessionIds(Map.of("type", "system"), "session_id", null, null);

        assertThat(ids.of(List.of(Map.of("type", "system", "session_id", "a b; rm -rf /")))).isNull();
        assertThat(SessionIds.isId("0b1f-42_x.y")).isTrue();
        assertThat(SessionIds.isId("")).isFalse();
    }
}
