package org.fuin.sokar.core.credential;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Credential#covers}: a credential is for its host and its path, and nothing that only begins alike.
 */
class CredentialTest {

    private static Credential at(String match) {
        return new Credential("forge", Credential.Kind.TOKEN, match, null, Credential.ANY, Credential.Source.VAULT);
    }

    @Test
    void aMatchCoversItsHostAndWhatIsBelowItNotAHostOrPathThatOnlyBeginsLikeIt() {

        // A plain prefix: 'https://github.com' covered 'https://github.com.evil.example', and '/org' '/org-evil'.
        assertThat(at("https://github.com").covers("https://github.com/org/repo")).isTrue();
        assertThat(at("https://github.com").covers("https://github.com")).isTrue();
        assertThat(at("https://github.com").covers("https://github.com.evil.example/x")).isFalse();
        assertThat(at("https://github.com/org").covers("https://github.com/org/repo")).isTrue();
        assertThat(at("https://github.com/org").covers("https://github.com/org-evil/repo")).isFalse();
        assertThat(at("https://github.com/org/").covers("https://github.com/org/repo")).isTrue();
    }
}
