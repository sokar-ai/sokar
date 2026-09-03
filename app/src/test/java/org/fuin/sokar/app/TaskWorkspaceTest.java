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
