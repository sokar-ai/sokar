package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Suggests}.
 */
class SuggestsTest {

    private final StringWriter written = new StringWriter();

    private final PrintWriter err = new PrintWriter(written);

    private static Suggests offering(List<String> names) {
        return new Suggests() {
            @Override
            public List<String> candidates() {
                return names;
            }

            @Override
            public String candidateLabel() {
                return "stopped tasks";
            }
        };
    }

    @Test
    void namesWhatTheCommandWouldHaveTaken() {

        Suggests.offer(err, offering(List.of("sokar-uc-shell-1", "sokar-uc-build-2")));
        err.flush();

        assertThat(written.toString())
                .contains("the stopped tasks here are")
                .contains("sokar-uc-shell-1")
                .contains("sokar-uc-build-2");
    }

    @Test
    void saysSoWhenThereAreNone() {

        // Printing nothing would read as a command with nothing to add, when what it means is
        // that there is nothing on this machine to name - usually the answer somebody needed.
        Suggests.offer(err, offering(List.of()));
        err.flush();

        assertThat(written.toString()).contains("there are no stopped tasks on this machine");
    }

    @Test
    void staysQuietWhenAskingTheRuntimeFails() {

        // This runs while a command is already failing, and asking podman can fail too. A stack
        // trace here would replace a useful message with a worse one.
        Suggests.offer(err, new Suggests() {
            @Override
            public List<String> candidates() {
                throw new IllegalStateException("podman is not there");
            }

            @Override
            public String candidateLabel() {
                return "tasks";
            }
        });
        err.flush();

        assertThat(written.toString()).isEmpty();
    }
}
