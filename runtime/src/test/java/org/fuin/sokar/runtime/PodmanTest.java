package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Podman}.
 */
class PodmanTest {

    private static final Project PROJECT =
            new Project("uc", "Ultimate Container", SecurityClass.GUARDED, "ubuntu:24.04", null);

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final Podman podman = new Podman(runner);

    @Test
    void asksPodmanWhereAContainerReachesTheHost() {

        // Both answers seen in the wild on the same project: pasta on podman 5, and the host's
        // own LAN address on podman 4. Assuming either one breaks the git gate on the other.
        runner.answering("run", "127.0.0.1\tlocalhost\n"
                + "169.254.1.2\thost.containers.internal host.docker.internal\n");
        assertThat(podman.hostAddressFromContainer("ubuntu:24.04")).contains("169.254.1.2");

        runner.answering("run", "10.1.0.194\thost.containers.internal host.docker.internal\n");
        assertThat(podman.hostAddressFromContainer("ubuntu:24.04")).contains("10.1.0.194");
    }

    @Test
    void answersNothingRatherThanGuessingWhenTheNameIsAbsent() {

        // The caller falls back to the old constant and says so. Returning a wrong address
        // silently would firewall the gate off with a hanging push as the only symptom.
        runner.answering("run", "127.0.0.1\tlocalhost\n");
        assertThat(podman.hostAddressFromContainer("ubuntu:24.04")).isEmpty();
    }

    @Test
    void startsAContainerWithSokarsOwnNetworkConfiguration(@TempDir Path directory) {

        // The mapping that lets the git gate bind loopback. On 'start' and nowhere else: podman
        // builds the pasta command line there, and setting it on 'create' does nothing at all.
        final Path configuration = directory.resolve("containers.conf");
        new Podman(runner, "podman", configuration).start("sokar-uc-1");

        assertThat(runner.only("start").environment())
                .containsEntry(LoopbackMapping.VARIABLE, configuration.toString());
        assertThat(configuration).content().contains("--map-host-loopback");
    }

    @Test
    void leavesPodmansConfigurationAloneWhenThereIsNoneToApply() {

        podman.start("sokar-uc-1");

        // Scoped on purpose: the operator's other containers must not gain a route to their own
        // loopback because Sokar is installed.
        assertThat(runner.only("start").environment()).doesNotContainKey(LoopbackMapping.VARIABLE);
    }

    @Test
    void asksHowPodmanConnectsARootlessContainer() {

        // Decides whether the gate can bind loopback: podman ignores a pasta option under
        // slirp4netns without saying so, and the symptom would be a push that hangs.
        runner.answering("info", "pasta\n");
        assertThat(podman.rootlessNetworkCmd()).contains("pasta");

        final FakeCommandRunner silent = new FakeCommandRunner();
        silent.answering("info", "");
        assertThat(new Podman(silent).rootlessNetworkCmd()).isEmpty();
    }

    @Test
    void refusesAPodmanTooOldToKeepThePromises() {

        // podman 4 has no pasta, so the host's loopback cannot be mapped into a container and the
        // git gate would bind every interface. Ubuntu 24.04 LTS ships 4.9.3 in universe and a
        // stable release never changes major version, so this is refused rather than degraded.
        runner.answering("version", "4.9.3");
        assertThat(podman.unsupportedVersion()).get().asString()
                .contains("4.9.3").contains("podman 5 or newer");

        final FakeCommandRunner five = new FakeCommandRunner();
        five.answering("version", "5.8.1");
        assertThat(new Podman(five).unsupportedVersion()).isEmpty();

        // A version that cannot be read is not one that can be trusted to be new enough.
        final FakeCommandRunner odd = new FakeCommandRunner();
        odd.answering("version", "podman-next");
        assertThat(new Podman(odd).unsupportedVersion()).get().asString().contains("podman-next");
    }

    @Test
    void saysWhyPodmanFailedRatherThanThatItDoesNotAnswer() {

        // podman ran and exited non-zero, and the one sentence that says why is what it wrote to
        // standard error. Reporting "does not answer" for every failure alike dropped exactly that.
        final FakeCommandRunner failing = new FakeCommandRunner();
        failing.failing("version", 125, "Error: cannot set up namespace\nsecond line\n");
        assertThat(new Podman(failing).unsupportedVersion()).get().asString()
                .contains("exit 125")
                .contains("cannot set up namespace")
                .doesNotContain("second line")
                .doesNotContain("does not answer");

        // Nothing on standard error is said too, rather than left as a dangling colon.
        final FakeCommandRunner mute = new FakeCommandRunner();
        mute.failing("version", 1, "");
        assertThat(new Podman(mute).unsupportedVersion()).get().asString()
                .contains("exit 1").contains("nothing on standard error");
    }

    @Test
    void readsTheVersionInAnExplicitFormat() {

        runner.answering("version", "5.7.0");

        assertThat(podman.version()).isEqualTo("5.7.0");
        // Scraping the default table would break on the next podman release.
        assertThat(runner.only("version").describe()).contains("--format");
    }

    @Test
    void buildsTheImageFromAGeneratedContainerfile(@TempDir Path context) {

        final String image = podman.buildImage(PROJECT, context);

        assertThat(image).isEqualTo("sokar/uc");
        assertThat(context.resolve("Containerfile")).exists();
        assertThat(runner.only("build").describe())
                .contains("--tag sokar/uc")
                .contains("--file");
    }

    @Test
    void writesTheContainerfileBeforeBuilding(@TempDir Path context) throws Exception {

        podman.buildImage(PROJECT, context);

        assertThat(Files.readString(context.resolve("Containerfile"))).contains("FROM ubuntu:24.04");
    }

    @Test
    void reportsAFailedBuildWithPodmansOwnMessage(@TempDir Path context) {

        runner.failing("build", 125, "Error: short-name resolution enforced but cannot prompt");

        assertThatThrownBy(() -> podman.buildImage(PROJECT, context))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("short-name resolution");
    }

    @Test
    void createsAContainerWithTheSecurityFlagsThatAreNotNegotiable() {

        podman.create(new ContainerSpec("sokar-uc-shell-1", "sokar/uc").command("sleep", "infinity"));

        final String line = runner.only("create").describe();
        assertThat(line)
                .contains("--security-opt no-new-privileges")
                .contains("--cap-drop ALL")
                .contains("--init")
                // Not 'pasta:<options>': that discards podman's own pasta defaults, and the
                // measured result was open egress with the ruleset still loaded.
                .contains("--network private");
    }

    @Test
    void passesAnnotationsThroughSoTheHooksCanSeeThem() {

        podman.create(new ContainerSpec("sokar-uc-shell-1", "sokar/uc")
                .annotation("org.fuin.sokar.sidecar", "/run/user/1000/sokar/x.json"));

        assertThat(runner.only("create").describe())
                .contains("--annotation org.fuin.sokar.sidecar=/run/user/1000/sokar/x.json");
    }

    @Test
    void pointsTheContainerAtSokarsOwnResolver() {

        podman.create(new ContainerSpec("sokar-uc-shell-1", "sokar/uc").resolver("127.0.0.1"));

        assertThat(runner.only("create").describe()).contains("--dns 127.0.0.1");
    }

    @Test
    void passesAnEnvironmentVariableToTheContainerWithoutPuttingItInTheArguments() {

        // An argument list is world-readable and an environment is not, so podman is told the
        // name and left to copy the value out of its own environment. Measured with real podman:
        // the value arrives byte for byte, spaces and padding included.
        podman.create(new ContainerSpec("sokar-uc-shell-1", "sokar/uc")
                .environment("ANTHROPIC_API_KEY", "sokar_pt_phantom"));

        assertThat(runner.only("create").describe())
                .contains("--env ANTHROPIC_API_KEY")
                .doesNotContain("sokar_pt_phantom");
        assertThat(runner.only("create").environment())
                .containsEntry("ANTHROPIC_API_KEY", "sokar_pt_phantom");
    }

    @Test
    void keepsAValueOutOfTheArgumentsOfAnAgentRunToo() {

        // The second exposure: the agent is started with 'podman exec', once per run, and the
        // phantom token was on that command line as well.
        podman.ask("sokar-uc-shell-1", Map.of("ANTHROPIC_API_KEY", "sokar_pt_phantom"),
                List.of("printenv", "ANTHROPIC_API_KEY"));

        assertThat(runner.only("exec").describe())
                .contains("--env ANTHROPIC_API_KEY")
                .doesNotContain("sokar_pt_phantom");
        assertThat(runner.only("exec").environment())
                .containsEntry("ANTHROPIC_API_KEY", "sokar_pt_phantom");
    }

    @Test
    void namesEveryVariableItCarries() {

        // Naming without carrying is the dangerous half: podman passes nothing at all for a name
        // it cannot resolve, so the container would come up missing a variable rather than
        // failing, and an agent missing its endpoint quietly uses its own.
        final ContainerSpec specification = new ContainerSpec("sokar-uc-shell-1", "sokar/uc")
                .environment("ONE", "first")
                .environment("TWO", "second");

        podman.create(specification);

        assertThat(runner.only("create").environment())
                .containsOnlyKeys(specification.environment().keySet().toArray(String[]::new));
    }

    @Test
    void relabelsVolumesForSelinux() {

        podman.create(new ContainerSpec("sokar-uc-shell-1", "sokar/uc")
                .volume(Path.of("/home/tester/src"), "/workspace"));

        // Without ':Z' the mount is unreadable on an enforcing host.
        assertThat(runner.only("create").describe()).contains("/home/tester/src:/workspace:Z");
    }

    @Test
    void reportsAMissingContainerAsEmptyRatherThanFailing() {

        runner.failing("inspect", 125, "no such container");

        assertThat(podman.idOf("sokar-uc-shell-1")).isEmpty();
        assertThat(podman.pidOf("sokar-uc-shell-1")).isEmpty();
    }

    @Test
    void readsTheContainerPid() {

        runner.answering("State.Pid", "48213");

        assertThat(podman.pidOf("sokar-uc-shell-1")).contains(48213L);
    }

    @Test
    void treatsAStoppedContainersZeroPidAsAbsent() {

        // podman reports 0 for a created-but-not-running container. Returning that as a pid would
        // have a caller enter the namespaces of process 0.
        runner.answering("State.Pid", "0");

        assertThat(podman.pidOf("sokar-uc-shell-1")).isEmpty();
    }

    @Test
    void listsOnlyContainersSokarCreated() {

        runner.answering("ps", "sokar-uc-shell-1\nsome-other-container\nsokar-uc-build-2\n");

        assertThat(podman.sokarContainers())
                .containsExactly("sokar-uc-shell-1", "sokar-uc-build-2");
    }

    @Test
    void leavesALoginContainerOutOfTheTaskList() {

        // Reported from the test machine: 'sokar vault login' left a container behind and it
        // showed up as a task, which has a workspace, a gate and a clearance that a login has
        // none of. It stays in sokarContainers(), which is what a cleanup sweeps.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\n"
                + "sokar-login-1788886971400\tExited (0) 2 minutes ago\t1700000000\t1700000100\n");

        assertThat(podman.sokarTasks()).extracting(ContainerSummary::name)
                .containsExactly("sokar-uc-shell-1");
    }

    @Test
    void readsTheProjectAndClassOffTheContainerItself() {

        // Both used to come only from a sidecar in $XDG_RUNTIME_DIR, which the system destroys
        // when the user's last session ends - so after a reboot every surviving task listed "-"
        // for both. A label lives and dies with the container, which is the right lifetime.
        // The fields podman fills in for '{{index .Labels "..."}}', which is what the format
        // asks for. An earlier version parsed '{{.Labels}}' as 'k=v,k=v' - a shape podman never
        // emits; it renders Go's 'map[a:b c:d]'. The parser and this fixture agreed with each
        // other and with nothing else, and every task listed no project on a real machine.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");

        assertThat(podman.sokarTasks()).singleElement().satisfies(task -> {
            assertThat(task.project()).isEqualTo("uc");
            assertThat(task.securityClass()).isEqualTo("guarded");
        });
    }

    @Test
    void answersNothingForAContainerCarryingNoLabels() {

        // Created by a Sokar that did not write them. The sidecar is still the fallback, so this
        // must be absent rather than an empty string that would win over it.
        // podman leaves the field empty for a label the container does not carry.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\t\t\n");

        assertThat(podman.sokarTasks()).singleElement().satisfies(task -> {
            assertThat(task.project()).isNull();
            assertThat(task.securityClass()).isNull();
        });
    }

    @Test
    void asksPodmanForEachLabelRatherThanParsingItsMap() {

        // The fix for the above, and the thing worth pinning: other labels on the container -
        // buildah's, the image's, anything the operator added - cannot affect the answer, because
        // podman is asked for one value by name instead of handing over its whole map to parse.
        runner.answering("ps", "sokar-uc-shell-1\tUp 4 minutes\t1700000000\t0\tuc\toffline\n");
        podman.sokarTasks();

        assertThat(runner.only("ps").describe())
                .contains("{{index .Labels \"org.fuin.sokar.project\"}}")
                .contains("{{index .Labels \"org.fuin.sokar.class\"}}")
                .as("the whole map is never asked for, so its format cannot be got wrong")
                .doesNotContain("{{.Labels}}");
    }

    @Test
    void removalToleratesAContainerThatIsAlreadyGone() {

        runner.failing("stop", 125, "no such container");
        runner.failing("rm", 125, "no such container");

        // Cleanup runs on the way out. A container that has vanished is the desired state.
        podman.remove("sokar-uc-shell-1");

        assertThat(runner.lines()).hasSize(2);
    }

    @Test
    void buildsTheAttachCommandWithoutRunningIt() {

        final List<String> arguments = podman.attachArguments("sokar-uc-shell-1", "/bin/bash");

        assertThat(arguments)
                .containsExactly("podman", "exec", "--interactive", "--tty", "sokar-uc-shell-1", "/bin/bash");
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void namesTheTaskInTheShellPrompt() {

        // A container hostname says nothing about which task it is, and an operator with several
        // shells open has no other way to tell them apart.
        final String line = String.join(" ",
                podman.attachArguments("box", "/bin/bash", "agent-cli", "myproject/shell"));

        assertThat(line).contains("myproject/shell");
        assertThat(line).contains("PS1=");
    }

    @Test
    void restoresTheTerminalBeforeHandingOver() {

        // The agent draws a full-screen interface; without this the shell that follows inherits
        // raw mode and the alternate screen, and the operator's display is scrambled.
        final String line = String.join(" ",
                podman.attachArguments("box", "/bin/bash", "agent-cli", "p/t"));

        assertThat(line).contains("1049l");
        assertThat(line).contains("stty sane");

        // Reported from the machine: the shell WAS running and did not look like it - the last
        // output at the bottom, the cursor at the top, and nothing responding where somebody
        // was typing. Three of these were missing.
        assertThat(line)
                // The scrolling region a full-screen app sets. Left in place it confines every
                // later line to a band, which is what made the session look hung.
                .contains("[r")
                // Autowrap, or long lines overwrite themselves instead of wrapping.
                .contains("?7h")
                // Mouse reporting: left on, clicking types escape sequences into the shell.
                .contains("?1000l")
                // And the screen is cleared, because leaving the alternate screen restores a
                // cursor position that an agent which never entered it never saved.
                .contains("clear");
    }

    @Test
    void leavesTheTerminalAloneWhenNoAgentRuns() {

        // The negative case: with no full-screen program there is nothing to restore, and
        // emitting escape sequences into a fresh shell is noise.
        final String line = String.join(" ",
                podman.attachArguments("box", "/bin/bash", null, "p/t"));

        assertThat(line).doesNotContain("stty sane");
        assertThat(line).contains("PS1=");
    }

    @Test
    void passesTheOperatorsTerminalWhenTheContainerCanResolveIt() {

        // Measured on 2026-09-12: 'podman exec -t' invents TERM=xterm and passes nothing else,
        // whatever the operator is actually sitting at. Everything inside then renders in eight
        // colours.
        runner.answering("podman exec box infocmp xterm-256color", "");
        final Podman probing = new Podman(runner, "podman", null,
                name -> "TERM".equals(name) ? "xterm-256color" : null);

        assertThat(probing.terminalFor("box"))
                .containsExactly("--env", "TERM=xterm-256color");
    }

    @Test
    void fallsBackWhenTheContainerHasNeverHeardOfTheTerminal() {

        // The reason the container is asked at all. The base image carries ncurses' base set and
        // nothing else - no alacritty, no xterm-kitty, no foot - and sending one of those names
        // to a container that cannot look it up is worse than saying nothing: a program that
        // cannot resolve its terminal falls back further than xterm would have.
        runner.failing("podman exec box infocmp alacritty", 1, "");
        runner.answering("podman exec box infocmp xterm-256color", "");
        final Podman probing = new Podman(runner, "podman", null,
                name -> "TERM".equals(name) ? "alacritty" : null);

        assertThat(probing.terminalFor("box"))
                .containsExactly("--env", "TERM=" + Podman.FALLBACK_TERMINAL);
    }

    @Test
    void saysNothingWhenTheContainerCannotResolveEitherName() {

        // An image with a terminfo set smaller than ncurses' base. Leaving podman's own TERM in
        // place is the honest answer; naming something it does not have is not.
        runner.failing("podman exec box infocmp alacritty", 1, "");
        runner.failing("podman exec box infocmp xterm-256color", 1, "");
        final Podman probing = new Podman(runner, "podman", null,
                name -> "TERM".equals(name) ? "alacritty" : null);

        assertThat(probing.terminalFor("box")).isEmpty();
    }

    @Test
    void aTerminalThatCanDoNothingIsNotPassedOn() {

        // TERM=dumb is what a pipe and a build log say. Passing it on would tell the agent to
        // render nothing at all, which is worse than podman's guess.
        final Podman probing = new Podman(runner, "podman", null,
                name -> "TERM".equals(name) ? "dumb" : null);

        assertThat(probing.terminalFor("box")).isEmpty();
        assertThat(runner.invocations()).as("and it did not even ask").isEmpty();
    }

    @Test
    void colourSupportRidesAlongBecauseItNamesNoTerminfoEntry() {

        runner.answering("podman exec box infocmp xterm-256color", "");
        final Podman probing = new Podman(runner, "podman", null,
                name -> switch (name) {
                    case "TERM" -> "xterm-256color";
                    case "COLORTERM" -> "truecolor";
                    default -> null;
                });

        assertThat(probing.terminalFor("box"))
                .containsExactly("--env", "TERM=xterm-256color", "--env", "COLORTERM=truecolor");
    }

    @Test
    void theContainerIsAskedOncePerContainerRatherThanPerAttach() {

        // Attaching, leaving and attaching again is the ordinary way to use this, and the answer
        // cannot change while the container lives.
        runner.answering("podman exec box infocmp xterm-256color", "");
        final Podman probing = new Podman(runner, "podman", null,
                name -> "TERM".equals(name) ? "xterm-256color" : null);

        probing.terminalFor("box");
        probing.terminalFor("box");

        assertThat(runner.invocations()).hasSize(1);
    }

    @Test
    void buildingTheArgumentsStillRunsNothing() {

        // The builders are handed straight to exec(), and the test above this one pins why the
        // asking is separate: a builder that ran a command would make every caller pay for a
        // round trip whether a person's terminal was on the other end or not.
        podman.attachArguments("box", List.of("/bin/bash"), List.of("--env", "TERM=xterm-256color"));
        podman.attachArguments("box", "/bin/bash", "agent-cli", "p/t", List.of());

        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void theTerminalArgumentsGoBeforeTheContainerName() {

        // podman reads them as options of 'exec'; after the container name they would be
        // arguments of the program instead, which is a different and very confusing failure.
        assertThat(podman.attachArguments("box", List.of("/bin/bash"),
                List.of("--env", "TERM=xterm-256color")))
                .containsExactly("podman", "exec", "--interactive", "--tty",
                        "--env", "TERM=xterm-256color", "box", "/bin/bash");
    }

    @Test
    void theAgentStartsAtTheTopOfTheTerminal() {

        // Reported on 2026-09-12: starting a task prints two dozen lines - the token, the gate,
        // every reachable host - and the agent then drew its full-screen interface into the
        // middle of them. Two programs sharing one screen, and neither of them wrong.
        final String script = String.join(" ",
                podman.attachArguments("box", "/bin/bash", "agent-cli", null, List.of()));

        assertThat(script).contains("\\033[H\\033[2J");
        assertThat(script.indexOf("\\033[H\\033[2J"))
                .as("cleared before the agent runs, not after").isLessThan(script.indexOf("agent-cli"));
    }

    @Test
    void clearingTheScreenKeepsTheScrollback() {

        // ESC[3J erases the scrollback and is what 'clear' sends on a modern terminfo. The launch
        // report carries the gate URL and what the container may reach, and once the agent owns
        // the screen, scrolling back is the only way to read it.
        final String script = String.join(" ",
                podman.attachArguments("box", "/bin/bash", "agent-cli", null, List.of()));

        assertThat(script).doesNotContain("[3J");
    }

    @Test
    void aShellWithNoAgentIsNotCleared() {

        // Nothing has drawn over the report yet, and clearing it would take away the only copy of
        // what was just printed for no gain at all.
        final String script = String.join(" ",
                podman.attachArguments("box", "/bin/bash", null, null, List.of()));

        assertThat(script).doesNotContain("[2J");
    }

    @Test
    void anUnattendedRunNeverGetsAnInteractiveStdin() {

        // Measured against the shipped agents on 2026-09-12: pi and omp BLOCK at startup when
        // stdin is an open pipe - omp sat in 'readPipedInput' past 150 s, pi produced nothing for
        // 180 s - and both run normally with stdin closed. Without '-i' podman attaches nothing to
        // the container's stdin, so the agent sees it closed.
        //
        // This is one flag away from an unattended task that hangs forever, and it would look like
        // a task that is WORKING: the agent's own "still starting" lines keep the log growing, so
        // the one signal anything outside can see says it is fine.
        final List<String> arguments = podman.executeArguments("box",
                java.util.Map.of("ANTHROPIC_API_KEY", "x"), List.of("claude", "-p", "do it"));

        assertThat(arguments).doesNotContain("--interactive").doesNotContain("-i");
        assertThat(arguments).doesNotContain("--tty").doesNotContain("-t");
        assertThat(arguments).startsWith("podman", "exec");
    }

    @Test
    void theWindowsShellSaysWhenItEndedRatherThanBeingGuessedAt() {

        // Why the shell says so rather than tmux being asked: not a race - measured on the Ubuntu
        // VM on 2026-09-12, has-session answered "gone" on the first probe every time - but the
        // direction of failure. A container that cannot be asked has no session either, and that
        // answer would remove a task.
        final String script = podman.attachScript("/bin/bash", "agent-cli", "p/t");

        assertThat(script)
                .as("cleared when the window starts, so it describes THIS session")
                .startsWith("rm -f " + Containerfile.SESSION_ENDED);
        assertThat(script)
                .as("written after the shell returns")
                .endsWith("/bin/bash -l; : > " + Containerfile.SESSION_ENDED);
    }

    @Test
    void theShellIsNotExecdOrNothingCouldRunAfterIt() {

        // 'exec' replaces the process, so the marker could never be written. This is the one line
        // that makes the mechanism work and the one an optimisation would put back.
        assertThat(podman.attachScript("/bin/bash", null, null)).doesNotContain("exec /bin/bash");
    }

    @Test
    void aContainerThatCannotBeAskedCountsAsNotFinished() {

        // Absence keeps the task. A killed session, a crashed container and a container that
        // cannot be reached all look the same from here, and all of them must keep work rather
        // than discard it.
        runner.failing("test -f", 1, "");

        assertThat(podman.sessionEnded("box")).isFalse();
    }

    @Test
    void aMarkerMeansTheWorkIsOver() {
        runner.answering("test -f", "");

        assertThat(podman.sessionEnded("box")).isTrue();
    }
}
