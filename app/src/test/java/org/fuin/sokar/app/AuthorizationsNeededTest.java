package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AuthorizationsNeeded}: a question for a person, raised once per credential until answered.
 */
class AuthorizationsNeededTest {

    @TempDir
    Path dir;

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> "XDG_STATE_HOME".equals(name) ? dir.resolve("state").toString() : null,
                dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void raisedOnceUntilAGrantLandsAndRaisedAgainWhenItEnds() throws Exception {
        final SokarContext context = context();

        AuthorizationsNeeded.raise(context, "search", "sokar-p-a", "p", AuthorizationsNeeded.NEVER);
        final String first = AuthorizationsNeeded.open(context).getFirst().get("at");
        Thread.sleep(5);
        AuthorizationsNeeded.raise(context, "search", "sokar-p-b", "p", AuthorizationsNeeded.NEVER);

        // A second refusal for the same credential keeps the first question: one person, one answer.
        assertThat(AuthorizationsNeeded.open(context)).singleElement().satisfies(question -> {
            assertThat(question).containsEntry("task", "sokar-p-a").containsEntry("state", "never");
            assertThat(question.get("at")).isEqualTo(first);
        });

        AuthorizationsNeeded.clear(context, "search");
        assertThat(AuthorizationsNeeded.open(context)).isEmpty();

        AuthorizationsNeeded.raise(context, "search", "sokar-p-a", "p", AuthorizationsNeeded.ENDED);
        assertThat(AuthorizationsNeeded.open(context)).singleElement()
                .satisfies(question -> assertThat(question).containsEntry("state", "ended"));
    }
}
