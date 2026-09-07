package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.fuin.sokar.core.project.Limits;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContainerSpec}, covering the flags that are not negotiable.
 */
class ContainerSpecTest {

    private List<String> arguments() {
        return new ContainerSpec("box", "ubuntu:24.04").command("sleep", "infinity").toArguments();
    }

    @Test
    void alwaysDropsPrivileges() {
        assertThat(arguments()).containsSequence("--security-opt", "no-new-privileges")
                .containsSequence("--cap-drop", "ALL");
    }

    @Test
    void namesAVariableOnTheCommandLineAndCarriesItsValueElsewhere() {

        // An argument list is world-readable - /proc/<pid>/cmdline is mode 444 and neither
        // supported distribution mounts /proc with hidepid, measured - and an environment is not.
        // Podman copies the value from its own environment when it is given only the name.
        final ContainerSpec specification = new ContainerSpec("box", "ubuntu:24.04")
                .environment("SOKAR_TOKEN", "sokar_pt_a_secret_value");

        assertThat(specification.toArguments()).containsSequence("--env", "SOKAR_TOKEN");
        assertThat(specification.toArguments())
                .noneMatch(argument -> argument.contains("sokar_pt_a_secret_value"));
        assertThat(specification.environment())
                .containsEntry("SOKAR_TOKEN", "sokar_pt_a_secret_value");
    }

    @Test
    void neverPutsAValueBesideAVariableName() {

        // The rule rather than one instance of it: no --env argument may carry a value, whatever
        // is added here later.
        final List<String> arguments = new ContainerSpec("box", "ubuntu:24.04")
                .environment("ONE", "first")
                .environment("TWO", "second")
                .toArguments();

        for (int i = 0; i < arguments.size() - 1; i++) {
            if ("--env".equals(arguments.get(i))) {
                assertThat(arguments.get(i + 1)).doesNotContain("=");
            }
        }
        assertThat(arguments).contains("--env");
    }

    @Test
    void alwaysAddsAnInitProcess() {

        // PID 1 is 'sleep infinity', which never calls wait(), so an orphan becomes a zombie
        // holding a PID for the life of the container.
        assertThat(arguments()).contains("--init");
    }

    @Test
    void limitsMemoryAndProcessesWithoutBeingAsked() {
        assertThat(arguments()).containsSequence("--memory", Limits.DEFAULT_MEMORY)
                .containsSequence("--pids-limit", String.valueOf(Limits.DEFAULT_PIDS));
    }

    @Test
    void passesNoMemoryFlagWhenTheProjectOptedOut() {

        // The negative case: an empty value would be passed to podman as a limit of nothing.
        final List<String> arguments = new ContainerSpec("box", "ubuntu:24.04")
                .limits(new Limits(null, null, 4096)).toArguments();

        assertThat(arguments).doesNotContain("--memory");
        assertThat(arguments).containsSequence("--pids-limit", "4096");
    }

    @Test
    void carriesTheProjectsOwnLimits() {
        assertThat(new ContainerSpec("box", "ubuntu:24.04")
                .limits(new Limits("2g", "1.5", 512)).toArguments())
                .containsSequence("--memory", "2g")
                .containsSequence("--cpus", "1.5")
                .containsSequence("--pids-limit", "512");
    }
}
