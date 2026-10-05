package org.fuin.sokar.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ShellWords}.
 */
class ShellWordsTest {

    @Test
    void quotesEveryWord() {
        assertThat(ShellWords.quote(List.of("claude", "--dangerously-skip-permissions")))
                .isEqualTo("'claude' '--dangerously-skip-permissions'");
    }

    @Test
    void keepsAnArgumentWithASpaceAsOneArgument() {

        // The reason this class exists. Joined with a space and left unquoted, the shell would
        // hand the agent two arguments where the manifest declared one.
        assertThat(ShellWords.quote(List.of("agent", "--flag", "two words")))
                .isEqualTo("'agent' '--flag' 'two words'");
    }

    @Test
    void survivesAnArgumentContainingASingleQuote() {

        // The one character single quoting cannot contain. Getting this wrong ends the quoting
        // early and hands the rest of the argument to the shell as syntax.
        assertThat(ShellWords.quote(List.of("say", "it's")))
                .isEqualTo("'say' 'it'\\''s'");
    }

    @Test
    void leavesNothingBehindForAnEmptyList() {
        assertThat(ShellWords.quote(List.of())).isEmpty();
    }

    @Test
    void doesNotLetAWordExpandOrSubstitute() {

        // Inside single quotes a shell interprets nothing, which is the whole point: an argument
        // is passed as written rather than as whatever the container's environment makes of it.
        assertThat(ShellWords.quote(List.of("echo", "$HOME", "`id`", "*")))
                .isEqualTo("'echo' '$HOME' '`id`' '*'");
    }
}
