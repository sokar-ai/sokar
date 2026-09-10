package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Main}'s arguments and refusals.
 * <p>
 * <strong>Nothing here can reach the API, by construction.</strong> An earlier version of this
 * test called the real entry point and asserted that it refused for want of a token. It passed
 * here, where nothing sets one, and on CI - where the workflow does - it authenticated against the
 * real project, ran {@code sweep --mine}, and deleted the server the build was running on. So the
 * way in is a {@link Supplier} the test controls, and every test below hands over one that fails
 * the test if anything asks it for a connection.
 */
class MainTest {

    /** Fails the test rather than opening anything, so a parsing test cannot become a sweep. */
    private static final Supplier<Hetzner> NEVER = () -> {
        throw new AssertionError("the arguments were accepted and something tried to connect");
    };

    @Test
    void saysHowToUseItWhenAskedForNothing() throws IOException {
        assertThat(Main.run(new String[0], NEVER)).isEqualTo(2);
    }

    @Test
    void refusesAnOptionItDoesNotKnowRatherThanIgnoringIt() throws IOException {
        // An ignored option in a sweep means deleting on a rule nobody asked for, or not
        // deleting on one they did.
        assertThat(Main.run(new String[] {"sweep", "--nonsense"}, NEVER)).isEqualTo(2);
    }

    @Test
    void refusesAnAgeWithNoNumberAfterIt() throws IOException {
        assertThat(Main.run(new String[] {"sweep", "--older-than"}, NEVER)).isEqualTo(2);
    }

    @Test
    void refusesACommandThatIsNotTheSweep() throws IOException {
        assertThat(Main.run(new String[] {"rent"}, NEVER)).isEqualTo(2);
    }

    @Test
    void saysWhereTheTokenShouldComeFromWhenThereIsNone() {
        // Never an argument: /proc/<pid>/cmdline is world readable and neither supported
        // distribution mounts /proc with hidepid.
        assertThatThrownBy(() -> Main.token(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REMOTE_BUILD");
        assertThatThrownBy(() -> Main.token("  "))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Main.token("a-token")).isEqualTo("a-token");
    }

    @Test
    void namesTheLegSoTwoMatrixLegsAreNotOneRun() {
        // Both legs share GITHUB_RUN_ID, so the leg is what makes a server's label unique - and
        // a sweep deletes by that label. Without it each leg would delete the other's machine.
        assertThat(Main.runId("12345", "ubuntu")).isEqualTo("12345-ubuntu");
        assertThat(Main.runId("12345", "fedora")).isEqualTo("12345-fedora");
        assertThat(Main.runId("12345", null)).isEqualTo("12345");
        assertThat(Main.runId(null, null)).startsWith("local-");
    }
}
