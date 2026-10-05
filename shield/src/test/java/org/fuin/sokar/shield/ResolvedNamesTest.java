package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ResolvedNames}.
 */
class ResolvedNamesTest {

    /** Real lines, including the IPv6 answer that reached an operator as a bare number. */
    private static final String LOG = """
            dnsmasq[881150]: query[A] api.example.test from 127.0.0.1
            dnsmasq[881150]: reply api.example.test is 93.184.216.34
            dnsmasq[881150]: reply api.example.test is 2607:6bc0::10
            dnsmasq[881150]: cached other.example.test is 10.0.0.1
            """;

    @Test
    void namesTheAddressTheResolverAnswered() {
        assertThat(ResolvedNames.nameFor(LOG, "2607:6bc0::10")).contains("api.example.test");
        assertThat(ResolvedNames.nameFor(LOG, "93.184.216.34")).contains("api.example.test");
    }

    @Test
    void readsACachedAnswerToo() {

        // A repeat answer is served from cache and logged differently; the container was still
        // told that name, which is what the operator is being asked about.
        assertThat(ResolvedNames.nameFor(LOG, "10.0.0.1")).contains("other.example.test");
    }

    @Test
    void answersNothingForAnAddressNobodyResolved() {

        // The case that matters: an agent connecting to a literal address was never told a name,
        // and inventing one would be worse than showing the number.
        assertThat(ResolvedNames.nameFor(LOG, "8.8.8.8")).isEmpty();
        assertThat(ResolvedNames.nameFor("", "8.8.8.8")).isEmpty();
    }

    @Test
    void doesNotConfuseAnAddressThatIsAPrefixOfAnother() {

        // '10.0.0.1' must not match a line answering '10.0.0.11'.
        assertThat(ResolvedNames.nameFor(
                "dnsmasq[1]: reply longer.example.test is 10.0.0.11", "10.0.0.1")).isEmpty();
    }
}
