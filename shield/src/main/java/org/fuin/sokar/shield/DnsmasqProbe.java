package org.fuin.sokar.shield;

import java.util.Locale;

/**
 * What the installed dnsmasq can do.
 * <p>
 * One capability matters: {@code --nftset}. It is how a domain the project or the agent declared
 * becomes reachable, not merely resolvable - dnsmasq adds every address it answers to the
 * firewall's allow set, so what the container is told and what it may reach cannot drift apart.
 * <p>
 * <strong>A dnsmasq without it fails silently.</strong> The configuration is accepted, names still
 * resolve, and every declared host is then dropped by the firewall - which looks like a network
 * fault, or like a clearance prompt that will not stop coming. That is why this is probed and
 * reported by {@code sokar doctor} rather than discovered by an operator. Terok probes the same
 * capability for the same reason.
 */
public final class DnsmasqProbe {

    /** Marker dnsmasq prints when the feature was compiled out. */
    private static final String ABSENT = "no-nftset";

    /** Marker dnsmasq prints when it is available. */
    private static final String PRESENT = "nftset";

    private DnsmasqProbe() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads dnsmasq's own capability report.
     * <p>
     * Parses rather than guesses from a version number: the feature is a compile-time option, so
     * two builds of the same version can differ. dnsmasq prints, for example:
     * <pre>
     * Compile time options: IPv6 GNU-getopt DBus no-UBus i18n IDN2 DHCP ... ipset nftset auth
     * </pre>
     * with {@code no-nftset} in place of {@code nftset} where it is missing. The negative form
     * contains the positive one as a substring, so it is checked first.
     *
     * @param versionOutput Whatever {@code dnsmasq --version} printed.
     * @return {@code true} when nftset support is reported.
     */
    public static boolean supportsNftSet(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) {
            return false;
        }
        for (final String line : versionOutput.split("\\R")) {
            final String text = line.toLowerCase(Locale.ROOT);
            if (!text.contains("compile time options")) {
                continue;
            }
            for (final String option : text.split("[\\s:]+")) {
                if (ABSENT.equals(option)) {
                    return false;
                }
                if (PRESENT.equals(option)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the command that asks dnsmasq what it supports.
     *
     * @return Command and arguments.
     */
    public static java.util.List<String> versionCommand() {
        return java.util.List.of("dnsmasq", "--version");
    }
}
