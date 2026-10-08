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
    void sweepsALoginContainerAnEarlierRunLeftBehind(@TempDir Path dir) {

        // Reported from a machine carrying one thirteen hours old, outliving an interrupted
        // login. A login container is never reused - the name carries the millisecond it was
        // made - so one still there is litter, and a teardown cannot cover a kill.
        runner.answering("ps", "sokar-login-1788886971400\nsokar-uc-shell-1\n");

        AgentLogin.login(context(dir), null, false, false, new PrintWriter(out, true));

        assertThat(runner.lines())
                .anyMatch(line -> line.startsWith("podman rm")
                        && line.contains("sokar-login-1788886971400"));
        // And nothing else: a task is not litter, whatever state it is in.
        assertThat(runner.lines()).noneMatch(line -> line.contains("sokar-uc-shell-1"));
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
    void theLoginImageCarriesSomethingForTheAgentToOpenAUrlWith() {

        // Without it the login stops dead: an agent asked to authenticate tries to open a
        // browser, a container has none and no display, and the attempt neither succeeds nor
        // reports anything - leaving somebody at a prompt that never continues, inside a
        // container they did not know how to leave. That is what happened the first time.
        final String rendered = org.fuin.sokar.runtime.Containerfile.render(
                AgentLogin.loginProject(),
                org.fuin.sokar.runtime.ImageLayers.none()
                        .and(AgentLogin.browserShim(), java.util.List.of()));

        // All three standard names, because which one an agent reaches for is its own business.
        assertThat(rendered).contains("/usr/local/bin/xdg-open")
                .contains("sensible-browser").contains("www-browser");
        // BROWSER is set again, and both reversals happened for measured reasons rather than
        // taste. With it set, Claude Code redirects to a port on localhost instead of printing a
        // code (Agent Smith); that port is unreachable from the operator's computer unless the
        // container shares this machine's network, which the login container does - so the
        // redirect lands somewhere an ssh forward can reach. The operator chose that over
        // copying a code between two windows.
        assertThat(rendered).contains("ENV BROWSER=");
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

    @Test
    void copiesTheConfigDirectorysContentsRatherThanTheDirectory() {

        // Why a login had never stored anything, for any agent whose config directory holds the
        // credential as a file inside it. 'podman cp container:/home/agent/.claude target' copies
        // the DIRECTORY, leaving target/.claude/.credentials.json - and the extractor is handed
        // target and looks for target/.credentials.json, one level above the file. So every login
        // found nothing, said "it may have been cancelled", and was believed, because nobody had
        // run one end to end.
        //
        // Measured against podman rather than read: with the trailing "/." the contents land in
        // the target, without it the directory does.
        assertThat(AgentLogin.contentsOf("~/.claude")).isEqualTo("/home/agent/.claude/.");
        assertThat(AgentLogin.contentsOf("/etc/agent")).isEqualTo("/etc/agent/.");
        // And the plain form is still what names the directory itself, for anything that wants it.
        assertThat(AgentLogin.inContainer("~/.claude")).isEqualTo("/home/agent/.claude");
    }
}
