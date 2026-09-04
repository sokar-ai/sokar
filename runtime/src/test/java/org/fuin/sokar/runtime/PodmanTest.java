package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.core.process.FakeCommandRunner;
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
    void passesAnEnvironmentVariableToTheContainer() {

        podman.create(new ContainerSpec("sokar-uc-shell-1", "sokar/uc")
                .environment("ANTHROPIC_API_KEY", "sokar_pt_phantom"));

        assertThat(runner.only("create").describe()).contains("--env ANTHROPIC_API_KEY=sokar_pt_phantom");
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
}
