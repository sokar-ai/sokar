package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for what {@link LiveTaskSteps} builds and reads, nowhere near a machine.
 */
class LiveTaskStepsTest {

    private static final String SECRET = "sk-or-v1-0123456789abcdef";

    @Test
    void startsATaskThatIsLeftRunningAndAsksNobody() {
        assertThat(LiveTaskSteps.startCommand("live", "claude", "openrouter"))
                .isEqualTo("timeout 900 sokar task start --project 'live' --repository 'live' --agent 'claude'"
                        + " --provider 'openrouter' --detach --clearance deny");
    }

    @Test
    void leavesTheProviderToTheAgentWhenNoneIsNamed() {
        assertThat(LiveTaskSteps.startCommand("live", "pi", null)).doesNotContain("--provider")
                .endsWith("--agent 'pi' --detach --clearance deny");
    }

    @Test
    void namesTheTaskBeforeItsOptionsWhenTheScenarioChoseOne() {
        assertThat(LiveTaskSteps.startCommand("image", "live", "pi", null))
                .startsWith("timeout 900 sokar task start 'image' --project 'live' --repository 'live'");
    }

    @Test
    void refusesATaskNameThatIsNotOne() {
        assertThatThrownBy(() -> LiveTaskSteps.startCommand("t; rm -rf ~", "live", "pi", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesSomethingNoProjectCouldBeCalled() {
        assertThatThrownBy(() -> LiveTaskSteps.startCommand("p; rm -rf ~", "pi", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheContainerAndTheStateDirectoryFromWhatAStartPrinted() {
        final String said = "resolver  /run/user/1000/sokar/tasks/live-1/dns.conf (3 domains)\n"
                + "sidecar   /run/user/1000/sokar/tasks/live-1/sidecar.json\n"
                + "image     localhost/sokar-live:abc\n"
                + "container sokar-live-1\n";

        assertThat(LiveTaskSteps.task(said)).hasValueSatisfying(task -> {
            assertThat(task.container()).isEqualTo("sokar-live-1");
            assertThat(task.state()).isEqualTo("/run/user/1000/sokar/tasks/live-1");
        });
    }

    @Test
    void aStartThatNamedNoContainerStartedNothing() {
        assertThat(LiveTaskSteps.task("sidecar   /run/user/1000/sokar/x/sidecar.json\nrefused: no credential\n"))
                .isEmpty();
    }

    @Test
    void leavesAPlaceholderForTheContainersShellAndPassesItsValueAsAVariable() {
        final String command = LiveTaskSteps.execCommand("sokar-live-1",
                "pi --model \"${SOKAR_E2E_MODEL}\" 'say hi'", Map.of("SOKAR_E2E_MODEL", "z-ai/glm'5"));

        assertThat(command).startsWith("timeout 300 podman exec --env 'SOKAR_E2E_MODEL=z-ai/glm'\\''5'")
                .contains(" 'sokar-live-1' sh -c ")
                .as("the value must not be pasted into the script").contains("${SOKAR_E2E_MODEL}");
    }

    @Test
    void refusesToPutASecretOnPodmansCommandLine() {
        final World world = new World();
        world.remember("SOKAR_E2E_OPENROUTER_API_KEY", SECRET);
        world.task(new World.Task("sokar-live-1", null, ""));

        assertThatThrownBy(() -> new LiveTaskSteps(world).theTasksContainerRuns("echo ${SOKAR_E2E_OPENROUTER_API_KEY}"))
                .isInstanceOf(AssertionError.class).hasMessageContaining("would be on a command line")
                .hasMessageNotContaining(SECRET);
    }

    @Test
    void theLogListingNeverCarriesTheSecret() {
        assertThat(LiveTaskSteps.logListing("/run/user/1000/sokar/tasks/live-1"))
                .contains("'/run/user/1000/sokar/tasks/live-1'").contains("base64 -w0")
                .doesNotContain(SECRET);
    }

    @Test
    void findsASecretSplitAcrossALineBreakInOneFile() {
        final World world = new World();
        world.remember("KEY", SECRET);
        final Map<String, String> files = new LinkedHashMap<>();
        files.put("/state/relay.log", "request POST /v1\nauth " + SECRET.substring(0, 10) + "\n"
                + SECRET.substring(10) + "\n");
        files.put("/state/vault.log", "request POST /v1\n");

        assertThat(LiveTaskSteps.leaks(files, text -> world.contains("KEY", text)))
                .as("line by line, the split value is invisible - which is why the check exists").isEmpty();
        assertThat(LiveTaskSteps.leaks(files, text -> world.containsAcrossLines("KEY", text)))
                .containsExactly("/state/relay.log");
    }

    @Test
    void neverJoinsTwoFilesIntoOneValue() {
        final World world = new World();
        world.remember("KEY", SECRET);
        final Map<String, String> files = new LinkedHashMap<>();
        files.put("/state/a.log", "x " + SECRET.substring(0, 10));
        files.put("/state/b.log", SECRET.substring(10) + " y");

        assertThat(LiveTaskSteps.leaks(files, text -> world.containsAcrossLines("KEY", text))).isEmpty();
    }

    @Test
    void decodesTheListingWhateverTheFilesContain() {
        final Base64.Encoder encoder = Base64.getEncoder();
        final String listing = encoder.encodeToString("/state/odd name.log".getBytes(StandardCharsets.UTF_8)) + "\t"
                + encoder.encodeToString("line one\n\tline two\n".getBytes(StandardCharsets.UTF_8)) + "\n";

        assertThat(LiveTaskSteps.files(listing)).containsExactly(Map.entry("/state/odd name.log", "line one\n\tline two\n"));
    }

    @Test
    void readsThatAgentsRowAndNotTheFirstInstallsLine() {
        final String supplyChain = """
                NAME         BINARY           LABEL                  FROM
                claude       claude           Claude Code            /usr/lib/sokar/agents/sokar-agent-claude
                             installs: 2.1.267
                               /usr/local/bin/claude
                omp          omp              Oh My Pi               /usr/lib/sokar/agents/sokar-agent-omp
                             installs: 18.1.16
                pi           pi               Pi                     /usr/lib/sokar/agents/sokar-agent-pi
                             installs: nothing
                """;

        assertThat(LiveTaskSteps.installs(supplyChain, "omp")).contains("18.1.16");
        assertThat(LiveTaskSteps.installs(supplyChain, "claude")).contains("2.1.267");
        assertThat(LiveTaskSteps.installs(supplyChain, "pi")).as("'nothing' is not a version").isEmpty();
        assertThat(LiveTaskSteps.installs(supplyChain, "absent")).as("an agent the machine does not have").isEmpty();
    }

    @Test
    void findsAComponentsVersionNestedInsideAnother() {
        final String bill = "{\"bomFormat\":\"CycloneDX\",\"components\":[{\"name\":\"adapter\",\"version\":\"1\"},"
                + "{\"name\":\"sokar-agent-pi-tree\",\"version\":\"1\",\"components\":"
                + "[{\"name\":\"pi-coding-agent\",\"version\":\"0.85.1\"}]}]}";

        assertThat(LiveTaskSteps.versionIn(bill, "pi-coding-agent")).contains("0.85.1");
        assertThat(LiveTaskSteps.versionIn(bill, "claude-code")).isEmpty();
    }


    @Test
    void readsTheBrokersLogInTheTasksOwnStateDirectory() {
        assertThat(LiveTaskSteps.brokerCommand("/run/user/1000/sokar/tasks/live 1"))
                .isEqualTo("grep -m 4 '^request ' '/run/user/1000/sokar/tasks/live 1/vault.log'");
    }

    @Test
    void aStartThatNamedItsContainerAndThenFailedHasNotStarted() {
        // The container line was enough: a start that printed it and then failed passed every step after it.
        assertThat(LiveTaskSteps.notStarted(1, "container sokar-live-1\nsokar: the hook failed\n"))
                .contains("exit 1");
        assertThat(LiveTaskSteps.notStarted(0, "container sokar-live-1\n")).isNull();
        assertThat(LiveTaskSteps.notStarted(0, "nothing\n")).isNotNull();
    }
}
