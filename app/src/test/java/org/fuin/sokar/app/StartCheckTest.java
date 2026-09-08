package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link StartCheck}, for the outcomes reachable without a running agent.
 * <p>
 * The rest - the provider refusals and the two credential ones - are tested where their rule
 * lives, in {@code SelectedProviderTest} and {@code CredentialWiringTest}, and end to end against
 * a real agent in the acceptance suite. Restating them here against a stand-in agent would test a
 * stand-in.
 */
class StartCheckTest {

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void saysSoWhenThereIsNoProjectFile(@TempDir Path dir) {

        // Checked first and cheaply: every other answer is about a run against a project, and
        // answering them for a project that is not there would be answering about nothing.
        final StartCheck.Result result =
                StartCheck.check(context(dir), dir.resolve("absent.yml"), null, null, null);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.NO_PROJECT_FILE);
        assertThat(result.ready()).isFalse();
        assertThat(result.detail()).contains("absent.yml");
    }

    @Test
    void answersWithoutAProjectFileWhenNoneIsGiven(@TempDir Path dir) {

        // The start dialog asks before a project is necessarily chosen, so the project is
        // optional and its absence is not the same as a missing file.
        assertThat(StartCheck.check(context(dir), null, null, null, null).outcome())
                .isEqualTo(StartCheck.Outcome.NO_AGENT);
    }

    @Test
    void saysNothingIsInstalledRatherThanFailing(@TempDir Path dir) throws Exception {

        // A machine with no agent is a normal machine, not a broken one - a task can still be
        // started as a shell - so this is an outcome and not an error.
        Files.createDirectories(dir.resolve("project"));
        final Path project = dir.resolve("project/project.yml");
        Files.writeString(project, "project:\n  name: \"uc\"\n  security_class: \"guarded\"\n");

        final StartCheck.Result result = StartCheck.check(context(dir), project, null, null, null);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.NO_AGENT);
        assertThat(result.detail()).contains("shell");
        assertThat(result.agent()).isEmpty();
    }

    @Test
    void namesTheAgentThatIsNotInstalled(@TempDir Path dir) {

        // The name somebody typed, back to them. "No agent is installed" when they asked for one
        // by name sends them looking at the machine rather than at what they typed.
        final StartCheck.Result result =
                StartCheck.check(context(dir), null, "not-installed", null, null);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.UNKNOWN_AGENT);
        assertThat(result.detail()).contains("not-installed");
    }

    @Test
    void readyIsTrueForExactlyOneOutcome(@TempDir Path dir) {

        // The bool and the outcome must not be able to disagree: a client is invited to branch on
        // whichever suits it, and two sources of one truth is how they come apart.
        for (final StartCheck.Outcome outcome : StartCheck.Outcome.values()) {
            final StartCheck.Result result = new StartCheck.Result(outcome, "", "", "", "");
            assertThat(result.ready())
                    .as("outcome %s", outcome)
                    .isEqualTo(outcome == StartCheck.Outcome.READY);
        }
    }

    @Test
    void everyAbsentValueTravelsAsAnEmptyStringNotAsNull(@TempDir Path dir) {

        // The same rule as the rest of this contract: "" and never the four characters "null",
        // which would render as a credential name somebody could go looking for.
        assertThat(StartCheck.check(context(dir), null, null, null, null).asMap())
                .containsEntry("agent", "").containsEntry("provider", "")
                .containsEntry("credential", "").containsEntry("ready", false)
                .containsKey("detail");
    }
}
