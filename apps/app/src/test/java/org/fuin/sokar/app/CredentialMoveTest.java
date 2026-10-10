package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.vault.VaultEntry;
import org.junit.jupiter.api.Test;

/**
 * Tests that a credential stored under its agent's name moves to its provider's without being entered again, and that a
 * move never overwrites what the provider's name holds already.
 */
class CredentialMoveTest {

    @Test
    void anEntryUnderTheAgentsNameMovesToTheProvidersWhole() {
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        final VaultEntry key = new VaultEntry("sk-1", "api-key", Map.of());
        entries.put("claude", key);

        final Map<String, VaultEntry> moved = CredentialChoice.moved(entries, "claude", "anthropic");

        assertThat(moved).doesNotContainKey("claude").containsEntry("anthropic", key);
    }

    @Test
    void aMoveNeverOverwritesWhatTheProvidersNameHolds() {
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        entries.put("claude", new VaultEntry("old", "api-key", Map.of()));
        final VaultEntry kept = new VaultEntry("new", "api-key", Map.of());
        entries.put("anthropic", kept);

        assertThat(CredentialChoice.moved(entries, "claude", "anthropic")).containsEntry("anthropic", kept)
                .containsKey("claude");
    }
}
