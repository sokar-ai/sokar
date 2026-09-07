package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.fuin.sokar.vault.VaultEntry;
import org.junit.jupiter.api.Test;

/**
 * Tests for what a task is told when it starts without a credential.
 * <p>
 * A launch does not stop for a missing credential - the task comes up and the agent fails to
 * authenticate from inside - so this line is the only warning anybody gets, and it has to name the
 * thing they actually have to do.
 */
class CredentialWiringTest {

    private static final Map<String, VaultEntry> HOLDING =
            Map.of("anthropic", VaultEntry.of("sk-test-value"));

    @Test
    void aLockedVaultSaysSoRatherThanClaimingTheCredentialIsMissing() {

        // The bug this replaced: a locked vault reads as an empty set, so the credential sitting
        // in it was reported absent. That sent somebody to store a credential they already had,
        // and it is the one confusion the interface asked never to be shown.
        assertThat(CredentialWiring.credentialUnavailable(Optional.empty(), "anthropic"))
                .contains("locked")
                .contains("sokar vault unlock")
                .doesNotContain("holds no credential");
    }

    @Test
    void anUnlockedVaultWithoutThisCredentialNamesTheOneItWouldNeed() {

        // The name, not the category: 'anthropic is not in the vault' is something somebody can
        // act on, 'a credential is missing' is not.
        assertThat(CredentialWiring.credentialUnavailable(Optional.of(HOLDING), "openai"))
                .contains("holds no credential")
                .contains("openai")
                .doesNotContain("locked");
    }

    @Test
    void anUnlockedVaultHoldingItSaysNothing() {

        assertThat(CredentialWiring.credentialUnavailable(Optional.of(HOLDING), "anthropic"))
                .isNull();
    }

    @Test
    void anUnlockedButEmptyVaultReportsTheCredentialAbsentRatherThanTheVaultLocked() {

        // The case the fix turns on. Both are "nothing to authenticate with", and only one of
        // them is fixed by unlocking - so an empty vault must not borrow the locked wording.
        assertThat(CredentialWiring.credentialUnavailable(Optional.of(Map.of()), "anthropic"))
                .contains("holds no credential")
                .doesNotContain("locked");
    }
}
