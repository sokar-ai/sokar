package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TaskWorkspace}.
 */
class TaskWorkspaceTest {

    @Test
    void testGateAddressIsNotResolved() {

        // The name podman uses does not exist on the host, so resolving it here returned null and
        // the firewall rule for the gate was silently left out. It must be a constant.
        assertThat(TaskWorkspace.gateAddress()).isEqualTo("169.254.1.2");
    }

    @Test
    void testReadsTheHostFromAnSshRemote() {

        // Not a URL, and the common shape for a git remote. The firewall and the resolver both
        // need the host, so getting this wrong means an online task cannot reach its upstream.
        assertThat(TaskRunCommand.upstreamHost("git@github.com:you/repo.git"))
                .isEqualTo("github.com");
    }

    @Test
    void testReadsTheHostFromAnHttpsRemote() {

        assertThat(TaskRunCommand.upstreamHost("https://github.com/you/repo.git"))
                .isEqualTo("github.com");
        assertThat(TaskRunCommand.upstreamHost("https://user@git.example.com:8443/repo.git"))
                .isEqualTo("git.example.com");
    }

    @Test
    void testSaysNothingRatherThanGuessing() {

        // A host it cannot read must not become a firewall rule for the wrong name.
        assertThat(TaskRunCommand.upstreamHost(null)).isNull();
        assertThat(TaskRunCommand.upstreamHost("   ")).isNull();
        assertThat(TaskRunCommand.upstreamHost("/srv/git/repo.git")).isNull();
    }

    @Test
    void testAGatedWorkspacePushesToAReviewRefAndADirectOneToABranch() {

        // The whole difference between guarded and online, in one place: a gated push lands
        // where no branch points, so nothing an operator is reading moves underneath them; a
        // direct push has no review step and so goes to a real branch.
        final org.fuin.sokar.core.project.Project online = new org.fuin.sokar.core.project.Project(
                "uc", "", org.fuin.sokar.core.project.SecurityClass.ONLINE, "ubuntu:24.04", null,
                "git@github.com:you/repo.git");
        final TaskWorkspace direct = TaskWorkspace.direct(online.upstream());

        assertThat(direct.gated()).isFalse();
        assertThat(direct.url(online)).isEqualTo("git@github.com:you/repo.git");
        assertThat(direct.environment(online, "shell"))
                .containsEntry("SOKAR_TASK_REF", "refs/heads/shell")
                .containsEntry("SOKAR_REMOTE_URL", "git@github.com:you/repo.git")
                // No gate means no gate token, so no credential header is invented for one.
                .doesNotContainKey("GIT_CONFIG_VALUE_0");
    }

    @Test
    void testVerifyAcceptsTheMappingPodmanWrites() {

        // Given
        final String hosts = """
                127.0.0.1\tlocalhost
                169.254.1.2\thost.containers.internal host.docker.internal
                """;

        // When & Then
        assertThat(TaskWorkspace.verify(hosts)).isNull();
    }

    @Test
    void testVerifyReportsADifferentAddress() {

        // Given
        final String hosts = "10.0.2.2\thost.containers.internal\n";

        // When
        final String result = TaskWorkspace.verify(hosts);

        // Then
        assertThat(result).contains("10.0.2.2").contains("firewalled off");
    }

    @Test
    void testVerifyReportsAMissingEntry() {

        // Given
        final String hosts = "127.0.0.1\tlocalhost\n";

        // When
        final String result = TaskWorkspace.verify(hosts);

        // Then
        assertThat(result).contains("no host.containers.internal entry");
    }

    @Test
    void testVerifyIgnoresCommentsAndBlankLines() {

        // Given
        final String hosts = """
                # added by podman
                
                169.254.1.2\thost.containers.internal
                """;

        // When & Then
        assertThat(TaskWorkspace.verify(hosts)).isNull();
    }
}
