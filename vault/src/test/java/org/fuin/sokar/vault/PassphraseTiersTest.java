package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Tests for the passphrase tiers.
 */
class PassphraseTiersTest {

    private static PassphraseSource none() {
        return Optional::empty;
    }

    private static PassphraseSource fixed(String value) {
        return () -> Optional.of(value.toCharArray());
    }

    @Test
    void takesTheFirstTierThatAnswers() {

        final PassphraseTiers tiers = new PassphraseTiers(none(), fixed("second"), fixed("third"));

        assertThat(tiers.passphrase()).hasValueSatisfying(
                value -> assertThat(value).containsExactly("second".toCharArray()));
    }

    @Test
    void failsSayingWhatItTried() {

        final PassphraseTiers tiers = new PassphraseTiers(
                new CommandPassphrase(new FakeCommandRunner(), null),
                new ConsolePassphrase("Passphrase: "));

        assertThatThrownBy(tiers::require)
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("passphrase-command")
                .hasMessageContaining("prompt");
    }

    @Test
    void readsTheFirstLineOfACommand() {

        final FakeCommandRunner runner = new FakeCommandRunner()
                .answering("sh -c", "hunter2\nsome trailing noise\n");

        assertThat(new CommandPassphrase(runner, "pass show vault").passphrase())
                .hasValueSatisfying(value -> assertThat(value).containsExactly("hunter2".toCharArray()));
    }

    @Test
    void skipsAnUnconfiguredCommand() {

        assertThat(new CommandPassphrase(new FakeCommandRunner(), null).passphrase()).isEmpty();
        assertThat(new CommandPassphrase(new FakeCommandRunner(), "  ").passphrase()).isEmpty();
    }

    @Test
    void refusesToFallThroughWhenAConfiguredCommandFails() {

        // Falling through would prompt for a passphrase the operator stored precisely so they
        // would not have to type it, and would look like the store was never configured.
        final FakeCommandRunner runner = new FakeCommandRunner()
                .failing("sh -c", 2, "gpg: decryption failed: No secret key");

        assertThatThrownBy(() -> new CommandPassphrase(runner, "pass show vault").passphrase())
                .isInstanceOf(VaultException.class)
                .hasMessageContaining("No secret key");
    }

    @Test
    void treatsAnEmptyAnswerAsNoAnswer() {

        final FakeCommandRunner runner = new FakeCommandRunner().answering("sh -c", "");

        assertThat(new CommandPassphrase(runner, "true").passphrase()).isEmpty();
    }

    @Test
    void promptReturnsNothingWithoutATerminal() {

        // Under a hook, in CI or from the daemon there is no console. Blocking forever on a prompt
        // nobody can see would be worse than failing.
        assertThat(new ConsolePassphrase("Passphrase: ").passphrase()).isEmpty();
    }
}
