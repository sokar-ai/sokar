package org.fuin.sokar.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * What may and may not pass a provider's answer on its way into a container.
 * <p>
 * Each case here is a way the earlier check could be walked past, and the last two are the ways
 * an over-eager one would withhold answers that carry nothing.
 */
class CredentialScanTest {

    private static boolean sees(String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new CredentialScan().sees(bytes, bytes.length);
    }

    @Test
    void aPlainCredentialFieldIsSeen() {
        assertThat(sees("{\"access_token\":\"real\"}")).isTrue();
        assertThat(sees("{\"refresh_token\": \"real\"}")).isTrue();
        assertThat(sees("{\"id_token\":\"real\"}")).isTrue();
    }

    @Test
    void anEscapedFieldNameIsTheSameField() {

        // Valid JSON, the same member to every parser, and invisible to a literal match. One
        // escaped character is enough to carry a provider credential into a container.
        assertThat(sees("{\"\\u0061ccess_token\":\"real\"}")).isTrue();
        assertThat(sees("{\"refresh_\\u0074oken\":\"real\"}")).isTrue();
        assertThat(sees("{\"\\u0069d_\\u0074oken\":\"real\"}")).isTrue();
    }

    @Test
    void upperCaseEscapesCountToo() {
        assertThat(sees("{\"\\u0041ccess_token\":\"x\"}")).isFalse();
        assertThat(sees("{\"\\u0061CCESS_token\":\"x\"}")).isFalse();
        assertThat(sees("{\"access_\\u0074oken\":\"x\"}")).isTrue();
    }

    @Test
    void aFieldAfterTheFirstFewThousandBytesIsStillSeen() {

        // The defect this class exists for: the earlier check read a bounded prefix and streamed
        // the rest unread, so a credential past that point crossed the boundary untouched.
        final CredentialScan scan = new CredentialScan();
        final byte[] filler = ("{\"text\":\"" + "x".repeat(20000) + "\",").getBytes(StandardCharsets.UTF_8);
        assertThat(scan.sees(filler, filler.length)).as("nothing yet").isFalse();
        final byte[] tail = "\"access_token\":\"real\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(scan.sees(tail, tail.length)).as("and now there is").isTrue();
    }

    @Test
    void aNameSplitAcrossTwoReadsIsStillOneName() {

        // A stream arrives in whatever pieces the network gives, and a field name is not obliged
        // to fall inside one of them.
        final CredentialScan scan = new CredentialScan();
        final byte[] head = "{\"acce".getBytes(StandardCharsets.UTF_8);
        final byte[] rest = "ss_token\":\"real\"}".getBytes(StandardCharsets.UTF_8);
        assertThat(scan.sees(head, head.length)).isFalse();
        assertThat(scan.sees(rest, rest.length)).isTrue();
    }

    @Test
    void anAnswerThatTalksAboutCredentialsIsNotOne() {

        // Inside a JSON string a quote arrives escaped, so this is an agent being told about a
        // field rather than being given one. Withholding it would make the proxy refuse ordinary
        // answers, which is how a security control gets switched off by whoever it annoys.
        assertThat(sees("{\"content\":\"set \\\"refresh_token\\\": in your config\"}")).isFalse();
    }

    @Test
    void ordinaryAnswersPassUntouched() {
        assertThat(sees("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}")).isFalse();
        assertThat(sees("data: {\"delta\":{\"text\":\"a token of appreciation\"}}\n\n")).isFalse();
    }
}
