package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DnsmasqProbe}.
 */
class DnsmasqProbeTest {

    /** Real output from dnsmasq 2.92, which has the feature. */
    private static final String WITH = """
            Dnsmasq Version 2.92  Copyright (c) 2000-2025 Simon Kelley
            Compile time options: IPv6 GNU-getopt DBus no-UBus i18n IDN2 DHCP DHCPv6 no-Lua TFTP \
            conntrack ipset nftset auth DNSSEC loop-detect inotify dumpfile
            """;

    /** The same shape with the feature compiled out. */
    private static final String WITHOUT = """
            Dnsmasq Version 2.80  Copyright (c) 2000-2018 Simon Kelley
            Compile time options: IPv6 GNU-getopt DBus i18n IDN DHCP DHCPv6 no-Lua TFTP \
            conntrack ipset no-nftset auth DNSSEC loop-detect inotify dumpfile
            """;

    @Test
    void recognisesADnsmasqThatSupportsNftSet() {
        assertThat(DnsmasqProbe.supportsNftSet(WITH)).isTrue();
    }

    @Test
    void recognisesADnsmasqThatDoesNot() {

        // The trap this test exists for: "no-nftset" CONTAINS "nftset", so a substring check
        // reports the opposite of the truth, and the failure it hides is silent - names resolve,
        // the firewall never opens, and nothing says why.
        assertThat(DnsmasqProbe.supportsNftSet(WITHOUT)).isFalse();
    }

    @Test
    void answersNoWhenThereIsNothingToRead() {

        // A missing dnsmasq, or output in a shape this does not recognise. Assuming support
        // would turn a loud "your dnsmasq is too old" into a silent network fault.
        assertThat(DnsmasqProbe.supportsNftSet(null)).isFalse();
        assertThat(DnsmasqProbe.supportsNftSet("")).isFalse();
        assertThat(DnsmasqProbe.supportsNftSet("Dnsmasq Version 2.92")).isFalse();
    }

    @Test
    void ignoresTheWordAnywhereButTheOptionsLine() {

        // Only dnsmasq's own capability line counts, not a copyright notice or a path that
        // happens to contain the word.
        assertThat(DnsmasqProbe.supportsNftSet("nftset is great\nVersion 2.92")).isFalse();
    }

    @Test
    void asksDnsmasqRatherThanGuessingFromAVersionNumber() {

        // It is a compile-time option, so two builds of one version can differ.
        assertThat(DnsmasqProbe.versionCommand()).containsExactly("dnsmasq", "--version");
    }
}
