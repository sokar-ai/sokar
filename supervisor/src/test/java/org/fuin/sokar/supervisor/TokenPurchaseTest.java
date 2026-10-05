package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TokenPurchase}: a token bought on the host, once, however many are waiting.
 */
class TokenPurchaseTest {

    private HttpServer server;

    private final AtomicInteger exchanges = new AtomicInteger();

    private final List<String> bodies = new CopyOnWriteArrayList<>();

    private final AtomicReference<String> answer = new AtomicReference<>(
            "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\",\"expires_in\":3600}");

    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.createContext("/token", exchange -> {
            exchanges.incrementAndGet();
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                // Slow enough that concurrent callers really do arrive while the first is buying.
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
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

    private TokenPurchase purchase(Clock clock) {
        return new TokenPurchase(new TokenPurchase.Client("http://127.0.0.1:" + server.getAddress().getPort() + "/token",
                "sokar-client", "client-secret-value", "repo read:org", null), HttpClient.newHttpClient(), clock);
    }

    @Test
    void buysATokenWithTheClientsCredentialsOnTheHost() throws Exception {
        final String token = purchase(Clock.systemUTC()).current();

        assertThat(token).isEqualTo("at-1");
        assertThat(bodies).singleElement().satisfies(body -> assertThat(body)
                .contains("grant_type=client_credentials").contains("client_id=sokar-client")
                .contains("client_secret=client-secret-value").contains("scope=repo+read%3Aorg"));
    }

    @Test
    void concurrentRequestsWithNoValidTokenProduceOneExchange() throws Exception {

        // Proven by making them concurrent, as the requirement asks, not by reading the code.
        final TokenPurchase purchase = purchase(Clock.systemUTC());
        final var pool = Executors.newFixedThreadPool(20);
        try {
            final List<Future<String>> waiting = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                waiting.add(pool.submit(purchase::current));
            }
            for (final Future<String> each : waiting) {
                assertThat(each.get()).isEqualTo("at-1");
            }
        } finally {
            pool.shutdown();
        }

        assertThat(exchanges.get()).isEqualTo(1);
        assertThat(purchase.bought()).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWhileTheServerRefusesAskItOnceAndAllHearTheRefusal() throws Exception {

        // The single flight held only for a purchase that succeeded: each waiting caller asked again in turn, so a
        // hanging server cost every agent its own timeout, one after another.
        status.set(503);
        answer.set("{\"error\":\"temporarily_unavailable\"}");
        final TokenPurchase purchase = purchase(Clock.systemUTC());
        final var pool = Executors.newFixedThreadPool(20);
        try {
            final List<Future<String>> waiting = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                waiting.add(pool.submit(purchase::current));
            }
            for (final Future<String> each : waiting) {
                org.assertj.core.api.Assertions.assertThatThrownBy(each::get)
                        .hasCauseInstanceOf(TokenPurchase.Refused.class);
            }
        } finally {
            pool.shutdown();
        }

        assertThat(exchanges.get()).isEqualTo(1);
    }

    @Test
    void anEndedGrantIsNotSpentAgain() throws Exception {

        final TokenPurchase purchase = refreshing(new CopyOnWriteArrayList<>());
        status.set(400);
        answer.set("{\"error\":\"invalid_grant\"}");

        for (int i = 0; i < 3; i++) {
            org.assertj.core.api.Assertions.assertThatThrownBy(purchase::current)
                    .isInstanceOf(TokenPurchase.Ended.class);
        }
        assertThat(exchanges.get()).isEqualTo(1);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, -5, Long.MAX_VALUE})
    void anExpiryThatMeansNothingIsTreatedAsUnstated(final long seconds) throws Exception {

        // Zero bought a token on every request, and a huge one threw out of the exchange unanswered.
        answer.set("{\"access_token\":\"at-1\",\"expires_in\":" + seconds + "}");
        final TokenPurchase purchase = purchase(Clock.systemUTC());

        assertThat(purchase.current()).isEqualTo("at-1");
        assertThat(purchase.current()).isEqualTo("at-1");
        assertThat(exchanges.get()).isEqualTo(1);
    }

    @Test
    void buysAgainShortlyBeforeTheTokenLapses() throws Exception {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-29T12:00:00Z"));
        final Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        final TokenPurchase purchase = purchase(clock);
        purchase.current();
        answer.set("{\"access_token\":\"at-2\",\"expires_in\":3600}");

        now.set(now.get().plus(Duration.ofMinutes(58)));
        assertThat(purchase.current()).as("still well inside its hour").isEqualTo("at-1");
        now.set(now.get().plus(Duration.ofMinutes(1)).plusSeconds(1));
        assertThat(purchase.current()).as("within a minute of lapsing").isEqualTo("at-2");
        assertThat(exchanges.get()).isEqualTo(2);
    }

    @Test
    void aRefusalIsReportedAsTheAuthorizationServersAndNeverEchoesTheSecret() {
        status.set(401);
        answer.set("{\"error\":\"invalid_client\",\"error_description\":\"bad secret client-secret-value\"}");

        assertThatThrownBy(() -> purchase(Clock.systemUTC()).current())
                .isInstanceOf(TokenPurchase.Refused.class)
                .hasMessageContaining("refused these client credentials (401, invalid_client)")
                .hasMessageNotContaining("client-secret-value");
    }

    @Test
    void anUnreachableAuthorizationServerIsSaidAsSuch() {
        server.stop(0);

        assertThatThrownBy(() -> purchase(Clock.systemUTC()).current())
                .isInstanceOf(TokenPurchase.Refused.class).hasMessageContaining("could not be reached");
    }

    @Test
    void aClientNeedsItsTokenUrlAndIdAndHttps() {
        assertThatThrownBy(() -> TokenPurchase.Client.of("s", Map.of("client_id", "x")))
                .hasMessageContaining("token_url and client_id");
        assertThatThrownBy(() -> TokenPurchase.Client.of("s", Map.of("client_id", "x", "token_url", "http://a/token")))
                .hasMessageContaining("must be https");
        assertThat(TokenPurchase.Client.of("s", Map.of("client_id", "x", "token_url", "https://a/token")).scopes())
                .isEmpty();
    }

    private TokenPurchase refreshing(List<String> rotations) {
        return new TokenPurchase(new TokenPurchase.Refresh("http://127.0.0.1:" + server.getAddress().getPort() + "/token",
                "sokar-client", null, "rt-1", rotations::add), HttpClient.newHttpClient(), Clock.systemUTC());
    }

    @Test
    void spendsAGrantsRefreshTokenAndKeepsTheOneTheServiceRotatesIn() throws Exception {
        answer.set("{\"access_token\":\"at-9\",\"refresh_token\":\"rt-2\",\"expires_in\":3600}");
        final List<String> rotations = new CopyOnWriteArrayList<>();

        assertThat(refreshing(rotations).current()).isEqualTo("at-9");
        assertThat(bodies).singleElement().satisfies(body -> assertThat(body).contains("grant_type=refresh_token")
                .contains("refresh_token=rt-1").doesNotContain("client_secret"));
        assertThat(rotations).as("the vault is told the one now in force").containsExactly("rt-2");
    }

    @Test
    void aRevokedGrantSaysItNeedsAuthorizingAgainRatherThanLookingLikeAWrongKey() {
        status.set(400);
        answer.set("{\"error\":\"invalid_grant\"}");

        assertThatThrownBy(() -> refreshing(new CopyOnWriteArrayList<>()).current())
                .as("its own kind, so whoever finds it can ask a person").isInstanceOf(TokenPurchase.Ended.class)
                .hasMessageContaining("no longer valid").hasMessageContaining("sokar vault authorize");
    }
}
