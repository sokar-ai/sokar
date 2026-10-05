package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.fuin.sokar.core.project.SecurityClass;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link NftRuleset}.
 * <p>
 * The two fixtures were loaded into a real container network namespace and checked to deny egress
 * while leaving loopback alone. Comparing byte for byte here is what keeps that true: a ruleset
 * that still parses but no longer blocks is the failure this guards against.
 * <p>
 * The port match was measured the same way, on nftables 1.1.6, against a veth pair with an sshd on
 * the far side and the address in {@code allowed_v4}: with {@code ip daddr @allowed_v4 accept} both
 * 443 and 22 connected, and with the port match 443 connected while 22 timed out. Dropped rather
 * than refused, which is what the chain policy and the NFLOG rule intend.
 */
class NftRulesetTest {

    @Test
    void rendersTheGuardedRuleset() throws IOException {

        final String rendered = new NftRuleset(SecurityClass.GUARDED)
                .resolver("10.89.0.1")
                .allowV4("140.82.112.0/20")
                .allowV6("2606:50c0::/32")
                .render();

        assertThat(rendered).isEqualTo(golden("ruleset-guarded.nft"));
    }

    @Test
    void rendersTheOfflineRuleset() throws IOException {

        assertThat(new NftRuleset(SecurityClass.OFFLINE).render())
                .isEqualTo(golden("ruleset-offline.nft"));
    }

    @Test
    void alwaysDropsByDefault() {

        for (final SecurityClass securityClass : SecurityClass.values()) {
            assertThat(new NftRuleset(securityClass).render())
                    .as("security class %s", securityClass)
                    .contains("policy drop;");
        }
    }

    @Test
    void offlineConsultsNoAllowSetAtAll() {

        // Adding an address to an offline project must not open anything: the class decides,
        // not the set contents.
        final String rendered = new NftRuleset(SecurityClass.OFFLINE)
                .allowV4("140.82.112.0/20")
                .resolver("10.89.0.1")
                .render();

        assertThat(rendered)
                .doesNotContain("daddr @allowed_v4")
                .doesNotContain("dport 53 accept");
    }

    @Test
    void logsEveryDropSoTheClearancePromptHasSomethingToShow() {

        assertThat(new NftRuleset(SecurityClass.GUARDED).render())
                .contains("log prefix \"sokar-drop \" group 1");
    }

    @Test
    void allowsBothAddressFamiliesWhenNotOffline() {

        final String rendered = new NftRuleset(SecurityClass.ONLINE)
                .allowV4("1.2.3.0/24")
                .allowV6("2001:db8::/32")
                .render();

        assertThat(rendered)
                .contains("elements = { 1.2.3.0/24 }")
                .contains("elements = { 2001:db8::/32 }")
                .contains("ip daddr @allowed_v4 tcp dport @allowed_ports accept")
                .contains("ip6 daddr @allowed_v6 tcp dport @allowed_ports accept");
    }

    @Test
    void reachesAnAllowedHostOnWebPortsOnly() {

        // The narrowing this exists for: an address enters the allow set from the resolver or from
        // a clearance approval, and neither of them can name a port. Accepting every port to it
        // would hand an agent git-over-ssh, which is a push that never passes the gate.
        final String rendered = new NftRuleset(SecurityClass.GUARDED).allowV4("1.2.3.0/24").render();

        assertThat(rendered)
                .contains("elements = { 80, 443 }")
                .doesNotContain("ip daddr @allowed_v4 accept")
                .doesNotContain("ip6 daddr @allowed_v6 accept")
                .doesNotContain("udp dport @allowed_ports");
    }

    @Test
    void widensOnlyWhenAskedTo() {

        assertThat(new NftRuleset(SecurityClass.GUARDED).port(8443).render())
                .contains("elements = { 80, 443, 8443 }");
    }

    @Test
    void leavesHostLocalAddressesOnEveryPort() {

        // Sokar's own endpoints inside the namespace are named by this call, and they are not on
        // the internet - a port match here would only break what Sokar itself put there.
        final String rendered = new NftRuleset(SecurityClass.GUARDED).localV4("127.0.0.0/8").render();

        assertThat(rendered)
                .contains("ip daddr 127.0.0.0/8 accept")
                .doesNotContain("ip daddr 127.0.0.0/8 tcp");
    }

    @Test
    void offlineOpensNoPortEitherWay() {

        assertThat(new NftRuleset(SecurityClass.OFFLINE).allowV4("1.2.3.0/24").render())
                .doesNotContain("dport @allowed_ports");
    }

    @Test
    void keepsEntriesInTheOrderTheyWereAdded() {

        // A set whose order depends on hashing makes the golden files unstable and every diff
        // unreadable.
        final String rendered = new NftRuleset(SecurityClass.ONLINE)
                .allowV4("10.0.0.0/8").allowV4("192.168.0.0/16").allowV4("172.16.0.0/12").render();

        assertThat(rendered).contains("elements = { 10.0.0.0/8, 192.168.0.0/16, 172.16.0.0/12 }");
    }

    private String golden(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/golden/" + name)) {
            assertThat(in).as("fixture /golden/" + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void onlyTheResolverItselfReachesTheUpstreamResolver() {

        // The rule matched the destination alone, so every process in the task could ask the upstream directly: data
        // left in query names, with no prompt and no log line. The resolver runs as the namespace's root, the agent
        // does not.
        final String rendered = new NftRuleset(SecurityClass.GUARDED).resolver("10.89.0.1").render();

        assertThat(rendered).contains("ip daddr 10.89.0.1 meta skuid 0 udp dport 53 accept")
                .contains("ip daddr 10.89.0.1 meta skuid 0 tcp dport 53 accept")
                .doesNotContain("ip daddr 10.89.0.1 udp dport 53 accept");
    }
}
