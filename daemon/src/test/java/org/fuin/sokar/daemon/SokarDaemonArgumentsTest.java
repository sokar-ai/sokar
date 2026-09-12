package org.fuin.sokar.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * What sokard does with an argument.
 * <p>
 * Every case here is "and it did not start a daemon", which is the whole point: the defect was
 * that asking the binary what it is started one on the asker's runtime socket.
 */
class SokarDaemonArgumentsTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private Integer answer(String... args) {
        return SokarDaemon.answer(args, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void helpIsAnsweredRatherThanStartingADaemon() {

        // Reported by the interface agent on 2026-09-12: 'sokard --help' listened. On a shared
        // machine that is a second Sokar on somebody else's account, started by a question.
        assertThat(answer("--help")).as("a null answer means 'now start the daemon'").isZero();
        assertThat(out()).contains("Usage: sokard").contains("never binds a network port");
        assertThat(err()).isEmpty();
    }

    @Test
    void theShortFormIsAnsweredToo() {
        assertThat(answer("-h")).isZero();
        assertThat(out()).contains("Usage: sokard");
    }

    @Test
    void versionIsAnsweredAndNamesTheProgramItIsFor() {

        // 'sokard', not 'sokar': the two are separate binaries out of one build, and a version
        // line that named the other one would be read as the CLI's.
        assertThat(answer("--version")).isZero();
        assertThat(out()).startsWith("sokard ");
    }

    @Test
    void anUnknownOptionIsRefusedRatherThanIgnored() {

        // Ignoring it started a daemon that did not do the thing that was asked for, and said
        // nothing about the option at all.
        assertThat(answer("--deamon")).isEqualTo(64);
        assertThat(err()).contains("unknown option '--deamon'").contains("Usage: sokard");
        assertThat(out()).isEmpty();
    }

    @Test
    void noArgumentsIsTheOneCaseThatStarts() {
        assertThat(answer()).as("null is what tells main to serve").isNull();
        assertThat(out()).isEmpty();
        assertThat(err()).isEmpty();
    }

    @Test
    void anUnknownOptionIsRefusedWhicheverSideOfAKnownOneItIsOn() {

        // Order must not decide it, and at first it did: acting on the first recognized option
        // meant '--version --deamon' printed a version and exited 0 - success reported for a
        // command line that contains a mistake. Every argument is checked before any is answered.
        assertThat(answer("--version", "--deamon")).isEqualTo(64);
        assertThat(err()).contains("unknown option '--deamon'");
        assertThat(answer("--deamon", "--version")).isEqualTo(64);
    }
}
