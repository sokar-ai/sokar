package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link InstallablePackages}.
 */
class InstallablePackagesTest {

    private FakeCommandRunner debian() {
        return new FakeCommandRunner()
                .answering("command -v apt-cache", "")
                .answering("command -v dpkg-query", "")
                .answering("apt-cache showpkg sokar-agent", """
                        Package: sokar-agent
                        Versions:

                        Reverse Depends:
                        Dependencies:
                        Provides:
                        Reverse Provides:
                        sokar-agent-claude 1.2.3
                        sokar-agent-omp 0.9.0
                        """)
                .answering("apt-cache showpkg sokar-transport", """
                        Package: sokar-transport
                        Reverse Provides:
                        sokar-message-transport-local 1.0.0
                        """)
                .answering("apt-cache show sokar-agent-claude",
                        "Version: 1.2.3\nDescription: Claude Code inside a Sokar task\n")
                .answering("apt-cache show sokar-agent-omp",
                        "Version: 0.9.0\nDescription: oh-my-pi inside a Sokar task\n")
                .answering("apt-cache show sokar-message-transport-local",
                        "Version: 1.0.0\nDescription: Carries messages between mailboxes here\n");
    }

    @Test
    void lists_what_the_repository_provides() {
        final FakeCommandRunner runner = debian()
                .failing("dpkg-query -W -f=${Version} sokar-agent-omp", 1, "no packages found")
                .answering("dpkg-query -W -f=${Version} sokar-agent-claude", "1.2.3")
                .failing("dpkg-query -W -f=${Version} sokar-message-transport-local", 1, "none");

        final var found = new InstallablePackages(runner).list();

        assertThat(found).extracting(InstallablePackages.Installable::name)
                .containsExactly("sokar-agent-claude", "sokar-agent-omp",
                        "sokar-message-transport-local");
        assertThat(found).extracting(InstallablePackages.Installable::kind)
                .as("what a person chooses between, not how it was asked for")
                .containsExactly("agent", "agent", "transport");
        assertThat(found.get(0).installed()).as("what is already here says so").isTrue();
        assertThat(found.get(1).installed()).isFalse();
        assertThat(found.get(0).description()).isEqualTo("Claude Code inside a Sokar task");
    }

    /**
     * A virtual package with several providers cannot be installed by its own name - the package
     * manager asks or refuses - so only real names may reach a person choosing.
     */
    @Test
    void never_offers_the_virtual_name_itself() {
        assertThat(new InstallablePackages(debian()).list())
                .extracting(InstallablePackages.Installable::name)
                .doesNotContain(InstallablePackages.PROVIDES_AGENT,
                        InstallablePackages.PROVIDES_TRANSPORT);
    }

    @Test
    void a_machine_with_no_package_manager_offers_nothing() {
        final FakeCommandRunner bare = new FakeCommandRunner()
                .failing("command -v", 1, "");

        assertThat(new InstallablePackages(bare).list()).isEmpty();
    }
}
