package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ProvidersCommand}. The rows themselves are {@link ProviderInventory}'s, and are
 * tested through the daemon that serializes them.
 */
class ProvidersCommandTest {

    @Test
    void saysWhatTheVaultHoldsWithoutEverSayingWhatItIs() {

        final Map<String, Object> underTheAgent = Map.of("authenticated", true,
                "credentialName", "claude", "credentialType", "api-key");

        assertThat(ProvidersCommand.credential(underTheAgent, true))
                .isEqualTo("stored as 'claude' (api-key)");
        assertThat(ProvidersCommand.credential(Map.of("authenticated", true,
                "credentialName", "anthropic", "credentialType", ""), true))
                .isEqualTo("stored as 'anthropic'");
        assertThat(ProvidersCommand.credential(Map.of("authenticated", false,
                "credentialName", "anthropic", "credentialType", ""), true)).isEqualTo("none");
        // Locked is not empty: "unlock first" and "store one" send somebody to different places.
        assertThat(ProvidersCommand.credential(underTheAgent, false))
                .isEqualTo("unknown - vault locked");
    }
}
