package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProviderRoute}.
 */
class ProviderRouteTest {

    private static ProviderRoute route(Map<String, String> unbrokerable) {
        return new ProviderRoute("https://example.test", "SOCK",
                Map.of("_default", "x-api-key"), Map.of("_default", ""), unbrokerable);
    }

    @Test
    void namesTheCredentialKindsItCannotCarry() {

        // Measured behavior of a real agent, declared as data rather than coded: with one kind
        // the agent ignores the base URL, so nothing reaches the proxy and the task fails inside
        // the container with a message about the operator's network.
        assertThat(route(Map.of("oauth", "it contacts the provider directly"))
                .unbrokerableReason("oauth")).isEqualTo("it contacts the provider directly");
    }

    @Test
    void saysNothingAboutAKindItCanCarry() {

        // The negative case, and the one that matters: a route answering for every kind would
        // refuse every task.
        assertThat(route(Map.of("oauth", "nope")).unbrokerableReason("api-key")).isNull();
        assertThat(route(Map.of("oauth", "nope")).unbrokerableReason(null)).isNull();
        assertThat(route(Map.of()).unbrokerableReason("oauth")).isNull();
    }

    @Test
    void carriesEveryKindWhenNoneIsDeclared() {

        // The four-argument form is what every agent that has not measured a limit uses.
        assertThat(new ProviderRoute("https://example.test", "SOCK", Map.of(), Map.of())
                .unbrokerable()).isEmpty();
    }
}
