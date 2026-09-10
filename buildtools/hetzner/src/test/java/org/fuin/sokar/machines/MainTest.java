package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
    void doesNotAnnotateAJobWhileATestIsCheckingARefusal() {
        // Every '::error::' line GitHub sees becomes an annotation on the job, including one a
        // unit test caused on purpose. Three green jobs carried red annotations that way.
        assertThat(Main.problem("unknown option: --nonsense")).startsWith("sokar: ");
        assertThat(Main.problem("unknown option: --nonsense")).doesNotContain("::error::");
    }

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
        assertThat(Main.run(new String[] {"sweep", "--older-than", "soon"}, NEVER)).isEqualTo(2);
    }

    @Test
    void readsAnAgeInTheSameUnitTheScriptItReplacesUsed() throws IOException {
        // 'sweep.py --older-than 60' means an hour and the workflow line says exactly that. Read
        // as hours it would mean sixty, and a forgotten server would bill for two and a half days
        // before anything swept it.
        try (StubApi stub = twoServers()) {
            assertThat(Main.run(new String[] {"sweep", "--older-than", "60", "--now"},
                    () -> Hetzner.against(stub.base(), "run-1"))).isZero();
            assertThat(stub.asked()).contains("DELETE /v1/servers/1");
            assertThat(stub.asked()).doesNotContain("DELETE /v1/servers/2");
        }
    }

    @Test
    void keepsTheAgeWhenAskedToActuallyDelete() throws IOException {
        // '--now' means "delete rather than say", not "delete everything whatever its age".
        // Ignoring the age here would take out the servers of every run in flight.
        try (StubApi stub = twoServers()) {
            Main.run(new String[] {"sweep", "--now"}, () -> Hetzner.against(stub.base(), "run-1"));
            assertThat(stub.asked()).doesNotContain("DELETE /v1/servers/2");
        }
    }

    @Test
    void deletesNothingUnlessToldTo() throws IOException {
        // Dry by default, and a non-zero exit so a scheduled run that found something it was not
        // allowed to remove is visible rather than quietly green.
        try (StubApi stub = twoServers()) {
            assertThat(Main.run(new String[] {"sweep"},
                    () -> Hetzner.against(stub.base(), "run-1"))).isEqualTo(1);
            assertThat(stub.asked()).doesNotContain("DELETE /v1/servers/1", "DELETE /v1/servers/2");
        }
    }

    /**
     * One server old enough to sweep and one too young, an hour apart either side.
     *
     * @return The stub.
     * @throws IOException If it cannot be started.
     */
    private static StubApi twoServers() throws IOException {
        final String old = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(90).toString();
        final String fresh = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).toString();
        return new StubApi()
                .answering("/servers?label_selector=sokar=ci&page=1&per_page=50", """
                    {"servers":[
                      {"id":1,"name":"forgotten","labels":{},"created":"%s"},
                      {"id":2,"name":"in use","labels":{},"created":"%s"}],
                     "meta":{"pagination":{"next_page":null}}}""".formatted(old, fresh))
                .answering("/servers/1", exchange ->
                        new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}"))
                .answering("/servers/2", exchange ->
                        new StubApi.Answer(200, "{\"action\":{\"id\":6,\"status\":\"success\"}}"));
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
