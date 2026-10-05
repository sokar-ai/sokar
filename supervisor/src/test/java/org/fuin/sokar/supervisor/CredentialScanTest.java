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

    @Test
    void aNameWaitsForItsColonAcrossAnyAmountOfWhitespace() {

        // JSON puts no limit on the space between a member name and its colon, and the carried
        // tail is finite - so a long enough run pushes the name out of the window before the colon
        // arrives and neither half matches. Measured through the proxy before this was fixed: the
        // credential reached the container. The wait is now a flag, which no length defeats.
        final CredentialScan scan = new CredentialScan();
        assertThat(feed(scan, "{\"access_token\"")).isFalse();
        for (int chunk = 0; chunk < 5; chunk++) {
            assertThat(feed(scan, " ".repeat(8192))).as("still waiting, chunk " + chunk).isFalse();
        }
        assertThat(feed(scan, ":\"real\"}")).isTrue();
    }

    @Test
    void aNameThatTurnsOutNotToBeAMemberStopsTheWait() {

        // The wait must end when something other than a colon arrives, or every mention of one of
        // these words at the end of a chunk would poison the rest of the answer.
        final CredentialScan scan = new CredentialScan();
        assertThat(feed(scan, "{\"content\":\"the field is called \"access_token\"")).isFalse();
        assertThat(feed(scan, "   and you set it yourself\"}")).isFalse();
        assertThat(feed(scan, " : not a member either")).isFalse();
    }

    @Test
    void whitespaceMeansWhatJsonMeansByIt() {

        // The wait and the pattern used two different notions of whitespace - an exact one and
        // Character.isWhitespace, which is much wider. A character JSON does not call whitespace
        // would then have kept the scan waiting for ever, withholding an answer no parser would
        // object to: safe for confidentiality, unsafe for availability.
        final CredentialScan waiting = new CredentialScan();
        assertThat(feed(waiting, "{\"access_token\"")).isFalse();
        assertThat(feed(waiting, "\u00A0 and then some prose")).as("the wait ends").isFalse();
        assertThat(feed(waiting, ":\"not a member\"")).as("and does not resume").isFalse();

        // And the same bytes in one chunk are judged the same way, which is the point of having
        // one set rather than two: where the chunk boundary falls must not decide the answer.
        assertThat(sees("{\"access_token\"\u00A0 and then some prose:\"not a member\"")).isFalse();
    }

    @Test
    void aCredentialUnderANameNobodyWatchesPassesThrough() {

        // Not a defect to fix here, a limit to see. The scan decides on a list of member names, so
        // a provider that calls its credential something else is not covered - and that is the
        // whole of what this guard claims. Written as a test rather than as prose so that anyone
        // who believes they have closed it finds out here.
        assertThat(sees("{\"session_key\":\"sk-live-REAL\"}")).isFalse();
        assertThat(sees("{\"api_key\":\"sk-live-REAL\"}")).isFalse();
    }

    /**
     * Feeds one chunk to a scan.
     *
     * @param scan The scan.
     * @param text The chunk.
     * @return What it reported.
     */
    private static boolean feed(CredentialScan scan, String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return scan.sees(bytes, bytes.length);
    }
}
