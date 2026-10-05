package org.fuin.sokar.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Asterisks}: what was typed is what every other way of giving a passphrase reads, one star per
 * character.
 */
class AsterisksTest {

    private static String typed(final String keys, final ByteArrayOutputStream shown) throws Exception {
        final char[] secret = Asterisks.typed(new ByteArrayInputStream(keys.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(shown, true, StandardCharsets.UTF_8)).orElseThrow();
        return new String(secret);
    }

    @Test
    void aPassphraseBeyondAsciiIsReadAsTheCharactersTyped() throws Exception {

        // Each byte became a character of its own, so "Grüße" typed here opened nothing that "Grüße" made elsewhere.
        final ByteArrayOutputStream shown = new ByteArrayOutputStream();

        assertThat(typed("Grüße\r", shown)).isEqualTo("Grüße");
        assertThat(shown.toString(StandardCharsets.UTF_8)).isEqualTo("*****");
    }

    @Test
    void aBackspaceTakesBackOneCharacterNotOneByte() throws Exception {

        assertThat(typed("Grü\u007f\u007fx\r", new ByteArrayOutputStream())).isEqualTo("Gx");
    }
}
