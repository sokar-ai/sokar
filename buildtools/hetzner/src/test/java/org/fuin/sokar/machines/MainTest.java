package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Main}'s arguments, which are read before anything is contacted.
 */
class MainTest {

    @Test
    void saysHowToUseItWhenAskedForNothing() throws IOException {
        assertThat(Main.run(new String[0])).isEqualTo(2);
    }

    @Test
    void refusesAnOptionItDoesNotKnowRatherThanIgnoringIt() throws IOException {
        // An ignored option in a sweep means deleting on a rule nobody asked for, or not
        // deleting on one they did.
        assertThat(Main.run(new String[] {"sweep", "--nonsense"})).isEqualTo(2);
    }

    @Test
    void refusesAnAgeWithNoNumberAfterIt() throws IOException {
        assertThat(Main.run(new String[] {"sweep", "--older-than"})).isEqualTo(2);
    }

    @Test
    void saysWhereTheTokenShouldComeFromWhenThereIsNone() {
        // Never an argument: /proc/<pid>/cmdline is world readable and neither supported
        // distribution mounts /proc with hidepid.
        assertThatThrownBy(() -> Main.run(new String[] {"sweep", "--mine"}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REMOTE_BUILD");
    }

    @Test
    void namesTheLegSoTwoMatrixLegsAreNotOneRun() {
        // Both legs share GITHUB_RUN_ID, so the leg is what makes a server's label unique - and
        // the cleanup deletes by that label.
        assertThat(Main.runId()).isNotBlank();
    }
}
