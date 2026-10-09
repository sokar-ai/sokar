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

    @Test
    void askingForTheAgentsSessionIsRefusedWhenItsCredentialCannotBeHad() {

        // The defect this pins: only UNATTENDED refused, so 'sokar task start' - whose --attach
        // defaults to 'agent' - warned "the vault is locked" and then built an image, a container
        // and a workspace, landing somebody in an agent that fails on its first request. The
        // refusal for a missing agent two checks later already argues this exact case: a person
        // who asked for an agent must not be handed something that is not one.
        assertThat(TaskLaunch.refusesWithoutCredential(org.fuin.sokar.wire.TaskMode.AGENT))
                .as("--attach agent asked for the agent itself").isTrue();
        assertThat(TaskLaunch.refusesWithoutCredential(org.fuin.sokar.wire.TaskMode.UNATTENDED))
                .as("nobody is watching an unattended run").isTrue();
    }

    @Test
    void aShellTaskStartsWithoutOneBecauseThatIsWhatItIsFor() {

        // Not symmetry for its own sake. Working inside the container by hand is exactly what a
        // shell task is for, and it is the fallback the agent refusal points people at - so
        // refusing it too would close the door the message holds open.
        assertThat(TaskLaunch.refusesWithoutCredential(org.fuin.sokar.wire.TaskMode.SHELL))
                .isFalse();
    }

    @Test
    void namesTheOneCommandThatGetsTheCredential() {
        // The operator on 2026-10-09 was told only that the vault held nothing for 'github-copilot'.
        final org.fuin.sokar.agent.api.ProviderDefinition copilot =
                org.fuin.sokar.agent.api.ProviderDefinitionReader.read(new java.io.StringReader("""
                        name: github-copilot
                        upstream: https://api.githubcopilot.com
                        dialects:
                          openai: ""
                        grant:
                          kind: oauth-device
                          device_authorization_url: https://github.com/login/device/code
                          token_url: https://github.com/login/oauth/access_token
                        """), "github-copilot.yaml");
        final org.fuin.sokar.agent.api.ProviderDefinition openrouter =
                org.fuin.sokar.agent.api.ProviderDefinitionReader.read(new java.io.StringReader("""
                        name: openrouter
                        upstream: https://openrouter.ai
                        dialects:
                          openai: "/api/v1"
                        """), "openrouter.yaml");

        assertThat(TaskLaunch.nextStep(null, false, copilot, "github-copilot"))
                .isEqualTo("Grant it first with 'sokar vault authorize github-copilot'; ");
        assertThat(TaskLaunch.nextStep("claude", true, null, "anthropic"))
                .isEqualTo("Sign in first with 'sokar vault login claude'; ");
        assertThat(TaskLaunch.nextStep("pi", false, openrouter, "openrouter"))
                .isEqualTo("Store its key first with 'sokar vault put openrouter'; ");
    }
}
