package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Tests for creating and destroying a machine - the path where a mistake bills 81 EUR a month.
 */
class LeaseTest {

    private static final Duration NO_WAIT = Duration.ofMillis(1);

    private static StubApi lookups() throws IOException {
        return new StubApi()
                .answering("/images?type=snapshot&label_selector=sokar=ci,os=ubuntu&page=1&per_page=50",
                        """
                        {"images":[{"id":11,"description":"ubuntu","status":"available",
                          "created":"2026-09-01T00:00:00+00:00"}],
                         "meta":{"pagination":{"next_page":null}}}""")
                .answering("/server_types?name=cpx41&page=1&per_page=50",
                        "{\"server_types\":[{\"id\":9}],\"meta\":{\"pagination\":{\"next_page\":null}}}")
                .answering("/datacenters?page=1&per_page=50", """
                        {"datacenters":[{"name":"nbg1-dc3",
                          "location":{"name":"nbg1","network_zone":"eu-central"},
                          "server_types":{"available":[9]}}],
                         "meta":{"pagination":{"next_page":null}}}""");
    }

    private static Spec spec(Credential credential) {
        return Spec.of("sokar-ci-test", "ubuntu", "build", credential);
    }

    @Test
    void waitsOutAFullProjectRatherThanFailingTheRun() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger attempts = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50",
                        keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> {
                    if (attempts.incrementAndGet() < 3) {
                        // Four repositories rent machines and nothing coordinates them, so
                        // overlapping runs collide. Failing here kills a run twenty minutes in,
                        // after it has already built everything.
                        return new StubApi.Answer(403, "{\"error\":{\"code\":"
                                + "\"resource_limit_exceeded\",\"message\":\"full\"}}");
                    }
                    return new StubApi.Answer(201, created());
                })
                .answering("/servers/77", exchange ->
                        new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}"))) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1", NO_WAIT);
            try (Lease rental = hetzner.acquire(spec(credential))) {
                assertThat(rental.address()).isEqualTo("1.2.3.4");
            }
            assertThat(attempts.get()).isEqualTo(3);
        }
    }

    @Test
    void givesUpOnAnythingWaitingCannotFix() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger attempts = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> {
                    attempts.incrementAndGet();
                    return new StubApi.Answer(400, "{\"error\":{\"code\":\"invalid_input\","
                            + "\"message\":\"image is not available\"}}");
                })) {
            // Burning ten minutes before reporting a bad image would be worse than failing now.
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1", NO_WAIT)
                    .acquire(spec(credential)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("image is not available");
            assertThat(attempts.get()).as("retried something that waiting cannot fix").isEqualTo(1);
        }
    }

    @Test
    void deletesTheServerEvenWhenTheWorkFailed() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger deleted = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201, created()))
                .answering("/servers/77", exchange -> {
                    deleted.incrementAndGet();
                    return new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}");
                })) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1", NO_WAIT);
            assertThatThrownBy(() -> {
                try (Lease rental = hetzner.acquire(spec(credential))) {
                    throw new IllegalStateException("the work failed on line three");
                }
            }).isInstanceOf(IllegalStateException.class);
            // The expensive mistake is a server that outlives a script which failed early.
            assertThat(deleted.get()).isEqualTo(1);
        }
    }

    @Test
    void keepsTheServerWhenAskedAndSaysItIsCostingMoney() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger deleted = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201, created()))
                .answering("/servers/77", exchange -> {
                    deleted.incrementAndGet();
                    return new StubApi.Answer(200, "{\"action\":{\"id\":5,\"status\":\"success\"}}");
                })) {
            final Hetzner hetzner = Hetzner.against(stub.base(), "run-1", NO_WAIT);
            try (Lease rental = hetzner.acquire(spec(credential).kept())) {
                assertThat(rental.address()).isEqualTo("1.2.3.4");
            }
            assertThat(deleted.get()).isZero();
        }
    }

    @Test
    void waitsForTheCreateToFinishBecauseCreatedIsNotReady() throws IOException {
        final Credential credential = Keys.generated();
        final AtomicInteger polls = new AtomicInteger();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201,
                        created("running")))
                .answering("/actions/5", exchange -> new StubApi.Answer(200,
                        "{\"action\":{\"id\":5,\"status\":\""
                                + (polls.incrementAndGet() < 2 ? "running" : "success") + "\"}}"))
                .answering("/servers/77", exchange -> new StubApi.Answer(200,
                        "{\"action\":{\"id\":6,\"status\":\"success\"}}"))) {
            try (Lease rental = Hetzner.against(stub.base(), "run-1", NO_WAIT)
                    .acquire(spec(credential))) {
                assertThat(rental.address()).isEqualTo("1.2.3.4");
            }
            assertThat(polls.get()).isGreaterThanOrEqualTo(2);
        }
    }

    @Test
    void failsWhenTheCreateActionItselfFails() throws IOException {
        final Credential credential = Keys.generated();
        try (StubApi stub = lookups()
                .answering("/ssh_keys?page=1&per_page=50", keys(Fingerprint.md5(credential)))
                .answering("/servers", exchange -> new StubApi.Answer(201, created("error")))
                .answering("/servers/77", exchange -> new StubApi.Answer(200,
                        "{\"action\":{\"id\":6,\"status\":\"success\"}}"))) {
            assertThatThrownBy(() -> Hetzner.against(stub.base(), "run-1", NO_WAIT)
                    .acquire(spec(credential)))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("ended as 'error'");
        }
    }

    private static String keys(String fingerprint) {
        return """
            {"ssh_keys":[{"id":2,"name":"ours","fingerprint":"%s"}],
             "meta":{"pagination":{"next_page":null}}}""".formatted(fingerprint);
    }

    private static String created() {
        return created("success");
    }

    private static String created(String status) {
        return """
            {"server":{"id":77,"name":"sokar-ci-test",
               "public_net":{"ipv4":{"ip":"1.2.3.4"}}},
             "action":{"id":5,"status":"%s"}}""".formatted(status);
    }
}
