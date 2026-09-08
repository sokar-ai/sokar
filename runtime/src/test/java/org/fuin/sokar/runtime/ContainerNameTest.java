package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ContainerName}.
 */
class ContainerNameTest {

    @Test
    void tellsALoginContainerApartFromATask() {

        // Reported from the test machine: 'sokar task list' showed a login container, which has
        // no workspace, no gate and no clearance, so every column of that list lies about it.
        final String login = ContainerName.login();
        assertThat(ContainerName.isSokar(login)).isTrue();
        assertThat(ContainerName.isLogin(login)).isTrue();
        assertThat(ContainerName.isTask(login)).isFalse();
    }

    @Test
    void countsAnOrdinaryContainerAsATask() {

        final String task = ContainerName.of(
                new org.fuin.sokar.core.project.Project("utils4j", "Utils",
                        org.fuin.sokar.core.project.SecurityClass.GUARDED, "ubuntu:24.04", null),
                "shell", "25471");
        assertThat(task).isEqualTo("sokar-utils4j-shell-25471");
        assertThat(ContainerName.isSokar(task)).isTrue();
        assertThat(ContainerName.isLogin(task)).isFalse();
        assertThat(ContainerName.isTask(task)).isTrue();
    }

    @Test
    void countsNothingOutsideSokarAsEither() {

        // The prefix is what keeps a cleanup off somebody else's containers.
        assertThat(ContainerName.isSokar("postgres")).isFalse();
        assertThat(ContainerName.isTask("postgres")).isFalse();
        assertThat(ContainerName.isLogin("login-1")).isFalse();
    }

    @Test
    void namesALoginAfterTheProjectItNeverHas() {

        assertThat(ContainerName.login()).startsWith("sokar-login-");
        assertThat(ContainerName.login().substring("sokar-login-".length()))
                .containsOnlyDigits();
    }

    @Test
    void doesNotMistakeATaskInAProjectCalledLoginForOne() {

        // 'login' passes the project name check, so this name is reachable. Matching on the
        // prefix alone would hide a real task from 'task list' and make attach and stop refuse
        // it - a project nobody could work in, for the sake of its name.
        assertThat(ContainerName.isLogin("sokar-login-shell-25471")).isFalse();
        assertThat(ContainerName.isTask("sokar-login-shell-25471")).isTrue();

        // Even a task named entirely of digits keeps its hyphen before the run id.
        assertThat(ContainerName.isLogin("sokar-login-42-7")).isFalse();
        assertThat(ContainerName.isTask("sokar-login-42-7")).isTrue();
    }

    @Test
    void doesNotTakeTheBarePrefixForALogin() {

        // Nothing produces this name; it is here because "starts with the prefix" would say yes.
        assertThat(ContainerName.isLogin("sokar-login-")).isFalse();
    }
}
