package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.sokar.agent.api.ProviderDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Destination}: a service a credential is for that is not a model provider.
 */
class DestinationTest {

    private static final String SEARCH = """
            name: brave-search
            label: Brave Search
            upstream: https://api.search.brave.com
            auth_header: X-Subscription-Token
            """;

    @Test
    void readsWhereTheServiceIsAndWhereItsKeyGoes() {
        final Destination search = Destination.parse(SEARCH);

        assertThat(search.name()).isEqualTo("brave-search");
        assertThat(search.host()).isEqualTo("api.search.brave.com");
        assertThat(search.authHeader()).isEqualTo("X-Subscription-Token");
        assertThat(search.authPrefix()).isEmpty();
        assertThat(search.authQuery()).isNull();
    }

    @Test
    void aKeyMayGoInTheUrlInstead() {
        final Destination maps = Destination.parse("""
                name: maps
                upstream: https://maps.example.com
                auth_query: key
                """);

        assertThat(maps.authQuery()).isEqualTo("key");
    }

    @Test
    void refusesAServiceReachedInTheClear() {
        assertThatThrownBy(() -> Destination.parse("name: plain\nupstream: http://api.example.com\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("over https");
    }

    @Test
    void findsTheUsersOwnBeforeThePackagedOnesAndSkipsWhatItCannotRead(@TempDir Path dataHome) throws IOException {
        Files.createDirectories(dataHome.resolve("destinations"));
        Files.writeString(dataHome.resolve("destinations/brave.yaml"), SEARCH);
        Files.writeString(dataHome.resolve("destinations/broken.yaml"), "name: [");

        assertThat(Destination.all(dataHome)).containsOnlyKeys("brave-search");
    }

    @Test
    void aProviderIsADestinationToo() {
        final ProviderDefinition anthropic = new ProviderDefinition("anthropic", "Anthropic", "https://api.anthropic.com",
                Map.of("anthropic-messages", ""), Map.of("_default", "x-api-key"), Map.of("_default", ""), Map.of(),
                Map.of());

        final Destination resolved = Destination.resolve("anthropic", Map.of(), Map.of("anthropic", anthropic), null);

        assertThat(resolved).isNotNull();
        assertThat(resolved.authHeader()).isEqualTo("x-api-key");
        assertThat(Destination.resolve("nobody", Map.of(), Map.of("anthropic", anthropic), null)).isNull();
    }
}
