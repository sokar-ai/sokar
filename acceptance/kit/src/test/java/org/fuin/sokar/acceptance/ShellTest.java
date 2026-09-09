package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Shell}.
 */
class ShellTest {

    @Test
    void quotesSoAShellInterpretsNothing() {
        assertThat(Shell.quote("echo $HOME; rm -rf *")).isEqualTo("'echo $HOME; rm -rf *'");
    }

    @Test
    void survivesASingleQuoteInside() {
        // The closing trick: end the quote, escape the quote, reopen the quote.
        assertThat(Shell.quote("it's")).isEqualTo("'it'\\''s'");
    }
}
