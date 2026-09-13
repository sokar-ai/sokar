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
                "shell");
        assertThat(task).isEqualTo("sokar-utils4j-shell");
        assertThat(ContainerName.isSokar(task)).isTrue();
        assertThat(ContainerName.isLogin(task)).isFalse();
        assertThat(ContainerName.isTask(task)).isTrue();
    }

    @Test
    void namesOneContainerPerTaskSoStartingTwiceFindsTheFirst() {

        // The whole point of dropping the run id. It used to carry the launching process's pid,
        // so two invocations never shared a container and nothing could map a task to one -
        // 'start' would build a second beside the one that was named.
        final org.fuin.sokar.core.project.Project project =
                new org.fuin.sokar.core.project.Project("utils4j", "",
                        org.fuin.sokar.core.project.SecurityClass.GUARDED, "ubuntu:24.04", null);

        assertThat(ContainerName.of(project, "shell"))
                .isEqualTo(ContainerName.of(project, "shell"));
    }

    @Test
    void refusesATaskNamePodmanOrGitWouldRefuseLater() {

        // Reported from the interface: 'Foo Bar' was accepted, the image was built, and podman
        // refused the container name only then - leaving policy, resolver and sidecar behind.
        assertThat(ContainerName.refusal("utils4j", "Foo Bar")).get().asString()
                .contains("not a task name").contains("'foo-bar' would do");
        for (final String bad : new String[] { "Foo", "foo_bar", "foo.bar", "foo..bar", "foo/bar",
                "-foo", "foo-", "" }) {
            assertThat(ContainerName.refusal("utils4j", bad)).as(bad).isPresent();
        }
        // Only digits is the shape that marks a login container.
        assertThat(ContainerName.refusal("login", "123")).get().asString().contains("needs a letter");

        for (final String good : new String[] { "shell", "a", "build-2", "2nd-try", "foo-bar" }) {
            assertThat(ContainerName.refusal("utils4j", good)).as(good).isEmpty();
        }
    }

    @Test
    void refusesATaskNameWhoseContainerNameWouldNotFitASocketPath() {

        // 'sokar-' + 'utils4j' + '-' is 14 characters, so 51 more make exactly 65.
        final String fits = "a".repeat(51);
        assertThat(ContainerName.refusal("utils4j", fits)).isEmpty();
        assertThat(ContainerName.refusal("utils4j", fits + "a")).get().asString()
                .contains("66 characters").contains("65");
        // Without a project the length cannot be known, and is left to the start that has one.
        assertThat(ContainerName.refusal("", fits + "a")).isEmpty();
    }

    @Test
    void handsBackTheTaskInsideAContainerName() {

        // Carried to an interface rather than derived by it: the rule relating the two belongs
        // here and has already changed once.
        assertThat(ContainerName.taskIn("utils4j", "sokar-utils4j-shell")).isEqualTo("shell");
        assertThat(ContainerName.taskIn("utils4j", "sokar-other-shell")).isEmpty();
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
