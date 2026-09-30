package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Grants}: which endpoints a grant may use, and how a failure to reach one is said.
 */
class GrantsTest {

    @Test
    void httpsAnywhereAndPlainHttpOnlyOnLoopback() {
        assertThat(Grants.secure("https://auth.example.org/token")).isTrue();
        assertThat(Grants.secure("http://127.0.0.1:8080/token")).isTrue();
        assertThat(Grants.secure("http://localhost/token")).isTrue();
        assertThat(Grants.secure("http://[::1]:9000/token")).isTrue();
        assertThat(Grants.secure("http://auth.example.org/token")).as("leaves the machine unencrypted").isFalse();
        assertThat(Grants.secure("http://127.0.0.1.example.org/token")).isFalse();
        assertThat(Grants.secure("ftp://127.0.0.1/token")).isFalse();
        // A stand-in on the machine can now be measured against.
        assertThat(DeviceGrant.Client.of("-", Map.of("client_id", "x", "device_authorization_url",
                "http://127.0.0.1:1/device", "token_url", "http://127.0.0.1:1/token")).tokenUrl())
                .isEqualTo("http://127.0.0.1:1/token");
    }

    @Test
    void aServiceNobodyListensForIsSaidAsRefusedNotAsNull() throws IOException {
        final int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        assertThatThrownBy(() -> Grants.send(HttpClient.newHttpClient(), "http://127.0.0.1:" + port + "/token",
                Map.of("a", "b"))).isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageEndingWith("could not be reached: the connection was refused")
                .hasMessageNotContaining("null");
    }

    @Test
    void aHostThatDoesNotExistIsSaidAsSuch() {
        assertThatThrownBy(() -> Grants.send(HttpClient.newHttpClient(), "https://auth.invalid/token",
                Map.of("a", "b"))).isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageEndingWith("could not be reached: no such host");
    }

    @Test
    void aFailureWithNoMessageIsNamedByItsKind() {
        assertThat(Grants.cause(new IOException())).isEqualTo("IOException");
        assertThat(Grants.cause(new IOException("said"))).isEqualTo("said");
    }
}
