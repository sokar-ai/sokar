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

/**
 * Tests that a task's start refused for a missing credential offers the very command it would name - a grant, a
 * sign-in or a key typed without echo - runs it on this terminal, and checks again before it goes on.
 */
class CredentialOfferTest {

    @TempDir
    private Path dir;

    private final List<List<String>> ran = new ArrayList<>();

    private final boolean[] stored = {false};

    private TaskLaunch launch() {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        final SokarContext context = new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                command -> {
                    ran.add(command);
                    stored[0] = true;
                    return 0;
                });
        return new TaskLaunch(context, new TaskLaunch.Request("t", dir.resolve("project.yml"), null, null, null, 8, null,
                false, false, "prompt", true, org.fuin.sokar.wire.TaskMode.SHELL, null, null, null, 60, null));
    }

    @Test
    void theCommandIsTheOneTheRefusalNames() {
        assertThat(TaskLaunch.nextCommand("claude", true, null, "anthropic")).containsExactly("vault", "login", "claude");
        assertThat(TaskLaunch.nextCommand("pi", false, null, "openrouter")).containsExactly("vault", "put", "openrouter");
        assertThat(TaskLaunch.nextCommand(null, false, null, "")).isEmpty();
        assertThat(TaskLaunch.nextStep("pi", false, null, "openrouter"))
                .contains("'sokar " + String.join(" ", TaskLaunch.nextCommand("pi", false, null, "openrouter")) + "'");
    }

    @Test
    void atATerminalTheCommandRunsHereAndTheStartGoesOnOnceTheCredentialIsThere() {
        final StringWriter err = new StringWriter();
        final TaskLaunch launch = launch();
        final Offer.Remedy remedy = launch.credentialRemedy("no credential for 'openrouter'",
                List.of("vault", "put", "openrouter"), () -> stored[0]);

        assertThat(new Offer(question -> "", false, false, null).resolve(remedy, new PrintWriter(err, true))).isTrue();
        assertThat(ran).singleElement().satisfies(command -> assertThat(command.subList(1, command.size()))
                .containsExactly("vault", "put", "openrouter"));
    }

    @Test
    void withoutATerminalNothingRunsAndTheCommandIsNamed() {
        final StringWriter err = new StringWriter();
        final Offer.Remedy remedy = launch().credentialRemedy("no credential for 'openrouter'",
                List.of("vault", "put", "openrouter"), () -> stored[0]);

        assertThat(new Offer(Offer.Asker.NOBODY, false, false, null).resolve(remedy, new PrintWriter(err, true)))
                .isFalse();
        assertThat(ran).isEmpty();
        assertThat(err.toString()).contains("'sokar vault put openrouter'");
    }
}
