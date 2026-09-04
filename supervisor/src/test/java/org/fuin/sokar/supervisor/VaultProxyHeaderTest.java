package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for which upstream headers reach the agent.
 */
class VaultProxyHeaderTest {

    @Test
    void dropsHttp2PseudoHeaders() {

        // Measured: the upstream connection is HTTP/2 and ':status' was being repeated verbatim
        // into an HTTP/1.1 response. The agent rejected every reply as malformed while the
        // requests themselves were succeeding with 200.
        assertThat(VaultProxy.forwardable(":status")).isFalse();
        assertThat(VaultProxy.forwardable(":authority")).isFalse();
    }

    @Test
    void dropsHeadersThatDescribeTheOtherConnection() {
        assertThat(VaultProxy.forwardable("Transfer-Encoding")).isFalse();
        assertThat(VaultProxy.forwardable("connection")).isFalse();
        assertThat(VaultProxy.forwardable("content-length")).isFalse();
    }

    @Test
    void keepsWhatTheAgentNeeds() {

        // The negative case: dropping too much loses the content type and the request id, which
        // is what makes a provider error diagnosable at all.
        assertThat(VaultProxy.forwardable("content-type")).isTrue();
        assertThat(VaultProxy.forwardable("request-id")).isTrue();
        assertThat(VaultProxy.forwardable("anthropic-organization-id")).isTrue();
    }
}
