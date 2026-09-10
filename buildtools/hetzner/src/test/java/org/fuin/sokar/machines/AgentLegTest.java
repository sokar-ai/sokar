package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for how an agent's leg installs what it is testing.
 */
class AgentLegTest {

    @Test
    void installsTheAgentInTheSameCommandAsSokar() {
        // Neither 'dpkg -i' nor 'rpm -i' resolves a dependency, and installing sokar separately
        // by hand would prove less than an operator's own package manager does.
        assertThat(AgentLeg.install("ubuntu", "https://x/artifactory", "sokar-agent-claude"))
                .contains("apt-get install -y -qq sokar sokar-agent-claude");
        assertThat(AgentLeg.install("fedora", "https://x/artifactory", "sokar-agent-omp"))
                .contains("dnf install -y -q sokar sokar-agent-omp");
    }

    @Test
    void installsACandidateByPathInThatSameCommand() {
        // A package built in the run and published nowhere still goes in beside sokar, so the
        // repository, the index and 'Depends: sokar' are all still exercised.
        assertThat(AgentLeg.install("ubuntu", "https://x/artifactory", "/root/candidate/*.deb"))
                .contains("install -y -qq sokar /root/candidate/*.deb");
    }

    @Test
    void takesTheRepositoryAndItsKeyFromWhereThePackagesArePublished() {
        final String script = AgentLeg.install("ubuntu", "https://fuinorg.jfrog.io/artifactory",
                "sokar-agent-pi");
        assertThat(script).contains("https://fuinorg.jfrog.io/artifactory/sokar-dist-deb snapshots main");
        assertThat(script).contains("/api/security/keypair/sokar-packages/public");
    }

    @Test
    void usesEachDistributionsOwnRepositoryFormat() {
        assertThat(AgentLeg.install("fedora", "https://x", "p")).contains("/etc/yum.repos.d/sokar.repo");
        assertThat(AgentLeg.install("ubuntu", "https://x", "p")).contains("/etc/apt/sources.list.d/sokar.list");
    }

    @Test
    void quotesACredentialSoAShellTakesItWhole() {
        // It travels as an environment assignment on the far shell, never as an argument: argv is
        // readable by every process on that machine.
        assertThat(AgentLeg.quote("plain")).isEqualTo("'plain'");
        assertThat(AgentLeg.quote("it's")).isEqualTo("'it'\\''s'");
        assertThat(AgentLeg.quote("a b; rm -rf /")).isEqualTo("'a b; rm -rf /'");
    }

    @Test
    void leavesNoPlaceholderUnreplaced() {
        assertThat(AgentLeg.install("ubuntu", "https://x", "p")).doesNotContain("@");
        assertThat(AgentLeg.install("fedora", "https://x", "p")).doesNotContain("@");
    }
}
