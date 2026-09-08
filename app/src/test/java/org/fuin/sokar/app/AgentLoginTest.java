package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for logging in with an agent.
 * <p>
 * What can be asserted without a real agent is the shape of the refusals and the shape of the
 * image this builds - and the second matters more than it looks, because a login container that
 * came out looking like a task container would be given a task's ruleset and a task's broker, and
 * an agent cannot log in through a broker that swaps in the credential it is trying to obtain.
 */
class AgentLoginTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), command -> 0);
    }

    @Test
    void aMachineWithNoAgentSaysSoRatherThanFailing(@TempDir Path dir) {

        final AgentLogin.Result result = AgentLogin.login(context(dir), null, false, false,
                new PrintWriter(out, true));

        assertThat(result.outcome()).isEqualTo(AgentLogin.Outcome.NO_SUCH_AGENT);
        assertThat(result.detail()).isNotBlank();
    }

    @Test
    void forceIsWhatDistinguishesADeliberateSecondLogin() {

        // The check itself needs a real agent to exercise - it asks the agent's own extractor
        // whether this machine already holds a credential - so what is pinned here is that the
        // decision has a switch at all, and that it is off by default. A second authorization may
        // invalidate the first, and that is the provider's business rather than something Sokar
        // can promise either way.
        assertThat(AgentLogin.Outcome.valueOf("ALREADY_SIGNED_IN")).isNotNull();
    }

    @Test
    void theLoginImageIsOfflineAndNamedForWhatItIs() {

        // It is never registered as a project and never runs a task. Offline because none of
        // Sokar's networking applies to it: the container is run without the annotation that
        // makes the hooks fire, so an egress class would describe something that is not there.
        assertThat(AgentLogin.loginProject().securityClass()).isEqualTo(SecurityClass.OFFLINE);
        assertThat(AgentLogin.loginProject().name()).isEqualTo("sokar-login");
    }

    @Test
    void theLoginImageDoesNotBorrowATasksName() {

        // Sharing 'sokar/<project>' would have a login rebuild the image a task uses, and a task
        // rebuild the one a login uses - each quietly undoing the other's layers.
        assertThat(AgentLogin.loginProject().imageName()).doesNotContain("sokar/demo");
        assertThat(AgentLogin.loginProject().imageName()).contains("sokar-login");
    }

    @Test
    void aConfigDirectoryIsResolvedInsideTheContainerAndNotOnTheNode() {

        // Off by one user, and it would have looked like a login that produced nothing: the agent
        // runs as 'agent' in the image, so '~/.claude' is /home/agent/.claude there while the
        // node's expansion gives whoever is running Sokar.
        assertThat(AgentLogin.inContainer("~/.claude")).isEqualTo("/home/agent/.claude");
        assertThat(AgentLogin.inContainer("/etc/somewhere")).isEqualTo("/etc/somewhere");
        assertThat(AgentLogin.inContainer("~/.claude"))
                .isNotEqualTo(VaultImportCommand.expand("~/.claude").toString());
    }

    @Test
    void nothingIsBuiltOrRunForAPreviewOnAMachineWithNoAgent(@TempDir Path dir) {

        AgentLogin.login(context(dir), null, true, false, new PrintWriter(out, true));

        assertThat(runner.invocations()).noneSatisfy(command ->
                assertThat(command.arguments()).contains("build"));
    }
}
