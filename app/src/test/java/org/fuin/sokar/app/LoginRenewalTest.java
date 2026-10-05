package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.sokar.agent.api.Credential;
import org.fuin.sokar.supervisor.TokenPurchase;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link LoginCredentials} and {@link LoginRenewal}: an agent's own sign-in kept whole, and renewed on
 * the host, once, whichever task's broker gets there first.
 */
class LoginRenewalTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private HttpServer server;

    private final List<String> bodies = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> answer = new AtomicReference<>(
            "{\"access_token\":\"at-2\",\"refresh_token\":\"rt-2\",\"expires_in\":28800}");

    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/oauth/token", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            final byte[] payload = answer.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String tokenUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/oauth/token";
    }

    private Credential signIn(final Instant ends) {
        return new Credential("oauth", "at-1", Map.of(Credential.REFRESH_TOKEN, "rt-1",
                Credential.EXPIRES_AT, String.valueOf(ends.toEpochMilli()), Credential.TOKEN_URL, tokenUrl(),
                Credential.CLIENT_ID, "the-agents-client"));
    }

    private VaultFile vault(final Path dir, final Credential credential, final boolean own) {
        final VaultFile vault = new VaultFile(dir.resolve("vault.bin"));
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        LoginCredentials.put(entries, "anthropic", credential, own);
        vault.write(entries, PASSPHRASE);
        return vault;
    }

    private LoginRenewal renewal(final VaultFile vault, final Instant at) {
        return new LoginRenewal("anthropic", vault.read(PASSPHRASE).get("anthropic"), vault,
                VaultFile.Opener.passphrase(PASSPHRASE), HttpClient.newHttpClient(), Clock.fixed(at, ZoneOffset.UTC));
    }

    @Test
    void keepsWhatASignInLeftWithTheRefreshTokenHiddenBesideIt(@TempDir Path dir) {

        // Kept whole: both used to keep the token and its type and drop the rest, so nothing could renew it.
        final Map<String, VaultEntry> entries = vault(dir, signIn(NOW.plusSeconds(3600)), true).read(PASSPHRASE);

        assertThat(entries.get("anthropic").value()).isEqualTo("at-1");
        assertThat(entries.get("anthropic").settings()).containsEntry("expires_at", "2026-10-01T13:00:00Z")
                .containsEntry("token_url", tokenUrl()).containsEntry("client_id", "the-agents-client")
                .doesNotContainValue("rt-1");
        assertThat(entries.get(TaskSecrets.GRANT_PREFIX + "anthropic"))
                .isEqualTo(new VaultEntry("rt-1", "refresh-token", Map.of()));
    }

    @Test
    void keepsAnImportsEndButNeverWhatWouldRenewIt(@TempDir Path dir) {

        // An import copies what the agent on this machine keeps renewing; renewing the copy would spend the
        // refresh token the developer's own session renews with.
        final Map<String, VaultEntry> entries = vault(dir, signIn(NOW.plusSeconds(3600)), false).read(PASSPHRASE);

        assertThat(entries.get("anthropic").settings()).containsOnlyKeys("expires_at");
        assertThat(entries).doesNotContainKey(TaskSecrets.GRANT_PREFIX + "anthropic");
        assertThat(LoginCredentials.renewable(entries.get("anthropic"))).isFalse();
    }

    @Test
    void usesTheStoredTokenUntilShortlyBeforeItEndsAndAsksNobody(@TempDir Path dir) throws Exception {

        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(3600)), true);

        assertThat(renewal(vault, NOW).current()).isEqualTo("at-1");
        assertThat(bodies).isEmpty();
    }

    @Test
    void renewsOnTheHostWhenItNearsItsEndAndKeepsWhatTheServiceRotated(@TempDir Path dir) throws Exception {

        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(30)), true);

        assertThat(renewal(vault, NOW).current()).isEqualTo("at-2");

        assertThat(bodies).singleElement().asString().contains("grant_type=refresh_token")
                .contains("refresh_token=rt-1").contains("client_id=the-agents-client");
        final Map<String, VaultEntry> kept = vault.read(PASSPHRASE);
        assertThat(kept.get("anthropic").value()).isEqualTo("at-2");
        assertThat(kept.get("anthropic").settings()).containsEntry("expires_at", "2026-10-01T20:00:00Z");
        assertThat(kept.get(TaskSecrets.GRANT_PREFIX + "anthropic").value()).isEqualTo("rt-2");
    }

    @Test
    void takesTheTokenAnotherTasksBrokerRenewedInsteadOfSpendingTheRefreshTokenAgain(@TempDir Path dir)
            throws Exception {

        // Two tasks, two brokers, one refresh token the service rotates: the second must not renew with it.
        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(30)), true);
        final LoginRenewal first = renewal(vault, NOW);
        final LoginRenewal second = renewal(vault, NOW);

        assertThat(first.current()).isEqualTo("at-2");
        assertThat(second.current()).isEqualTo("at-2");

        assertThat(bodies).hasSize(1);
    }

    @Test
    void saysASignInTheServiceEndedAsOneToMakeAgain(@TempDir Path dir) {

        status.set(400);
        answer.set("{\"error\":\"invalid_grant\"}");
        final VaultFile vault = vault(dir, signIn(NOW.plusSeconds(30)), true);

        assertThatThrownBy(() -> renewal(vault, NOW).current()).isInstanceOf(TokenPurchase.Ended.class)
                .hasMessageContaining("sign in again with 'sokar vault login'");
        assertThat(vault.read(PASSPHRASE).get("anthropic").value()).isEqualTo("at-1");
    }
}
