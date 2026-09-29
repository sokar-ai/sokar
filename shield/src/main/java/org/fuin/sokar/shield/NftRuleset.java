package org.fuin.sokar.shield;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.fuin.sokar.core.project.SecurityClass;

/**
 * Generates the nftables ruleset loaded into a task container's network namespace.
 * <p>
 * The ruleset is generated on the host and written to a file before the container exists. The nft
 * hook only pipes it into {@code nft} inside the container's namespace, so the hook needs no
 * knowledge of policy and the policy can be inspected by an operator before anything runs.
 * <p>
 * <strong>The output chain drops by default.</strong> Everything else in this class widens that,
 * never narrows it, so a bug that loses a rule fails towards no connectivity rather than towards
 * an open container.
 * <p>
 * <strong>A name in the allow set opens web ports, not the host.</strong> Addresses reach the
 * allow sets from two places that both name a <em>host</em> and never a port: the resolver, which
 * adds each answer as it answers, and {@link EgressPolicy}, which adds one address when an
 * operator approves it. Accepting every port to those addresses would grant far more than either
 * caller meant - notably git over ssh, which would let an agent push straight past the gate. The
 * ports are therefore matched explicitly, the way the gate and resolver rules already do.
 */
public class NftRuleset {

    /** NFLOG group the reader listens on. */
    public static final int NFLOG_GROUP = 1;

    /** Log prefix on every dropped packet, used to recognize Sokar's own events. */
    public static final String DROP_PREFIX = "sokar-drop";

    private final SecurityClass securityClass;

    private final Set<String> allowedV4 = new LinkedHashSet<>();

    private final Set<String> allowedV6 = new LinkedHashSet<>();

    private final Set<String> resolvers = new LinkedHashSet<>();

    private final Set<String> gateEndpoints = new LinkedHashSet<>();

    private final Set<String> localV4 = new LinkedHashSet<>();

    /**
     * The ports a declared host is opened on, unless the project names others. Stated to the operator in
     * {@code doc/reach.md}, and a test holds the two together.
     */
    public static final List<Integer> DEFAULT_PORTS = List.of(80, 443);

    private final Set<Integer> ports = new LinkedHashSet<>(DEFAULT_PORTS);

    /**
     * Constructor with the project's security class.
     *
     * @param securityClass Decides whether any egress is possible at all.
     */
    public NftRuleset(SecurityClass securityClass) {
        this.securityClass = securityClass;
    }

    /**
     * Allows egress to an IPv4 address or network, on the allowed ports only.
     *
     * @param cidr Address or CIDR block.
     * @return This instance.
     */
    public NftRuleset allowV4(String cidr) {
        allowedV4.add(cidr);
        return this;
    }

    /**
     * Allows egress to an IPv6 address or network, on the allowed ports only.
     *
     * @param cidr Address or CIDR block.
     * @return This instance.
     */
    public NftRuleset allowV6(String cidr) {
        allowedV6.add(cidr);
        return this;
    }

    /**
     * Allows DNS queries to a resolver.
     *
     * @param address IPv4 address of the resolver.
     * @return This instance.
     */
    public NftRuleset resolver(String address) {
        resolvers.add(address);
        return this;
    }

    /**
     * Allows egress to a host-local address on every port.
     *
     * @param cidr Address or CIDR block inside the container's own namespace.
     * @return This instance.
     */
    public NftRuleset localV4(String cidr) {
        localV4.add(cidr);
        return this;
    }

    /**
     * Adds a port to the ones reachable at an allowed address.
     * <p>
     * The default is 80 and 443, which is what a package registry, a provider API and an https
     * clone need. Anything beyond that is a deliberate widening and belongs in the project's own
     * declaration rather than here.
     *
     * @param port TCP port.
     * @return This instance.
     */
    public NftRuleset port(int port) {
        ports.add(port);
        return this;
    }

    /**
     * Allows the container to reach Sokar's own git gate.
     * <p>
     * Separate from {@link #allowV4(String)} so it survives the security class: an offline project
     * still pushes to the gate, because the gate is on this machine and is the *reason* the
     * project can be offline. Blocking it would mean an offline agent could not commit at all.
     *
     * @param address Address the gate listens on.
     * @param port Port the gate listens on.
     * @return This instance.
     */
    public NftRuleset gate(String address, int port) {
        gateEndpoints.add(address + " tcp dport " + port);
        return this;
    }

    /**
     * Renders the ruleset.
     *
     * @return File content, ending in a line separator.
     */
    public String render() {

        final List<String> lines = new ArrayList<>();

        lines.add("# Generated by Sokar for security class '"
                + securityClass.name().toLowerCase() + "'. Do not edit.");
        lines.add("# Loaded inside the container's network namespace by the nft hook.");
        lines.add("");
        lines.add("table inet sokar {");
        lines.add("");
        lines.add("    set allowed_v4 {");
        lines.add("        type ipv4_addr");
        lines.add("        flags interval");
        lines.add(elements(allowedV4));
        lines.add("    }");
        lines.add("");
        lines.add("    set allowed_v6 {");
        lines.add("        type ipv6_addr");
        lines.add("        flags interval");
        lines.add(elements(allowedV6));
        lines.add("    }");
        lines.add("");
        lines.add("    set allowed_ports {");
        lines.add("        type inet_service");
        lines.add("        elements = { " + join(ports) + " }");
        lines.add("    }");
        lines.add("");
        lines.add("    chain output {");
        lines.add("        type filter hook output priority filter; policy drop;");
        lines.add("");
        lines.add("        # A reply to something the container already sent is not a new decision.");
        lines.add("        ct state established,related accept");
        lines.add("");
        lines.add("        # Loopback is inside the namespace, so this reaches nothing outside it.");
        lines.add("        oif \"lo\" accept");

        if (!localV4.isEmpty()) {
            lines.add("");
            lines.add("        # Host-local addresses, on every port: these name this namespace,");
            lines.add("        # not somewhere on the internet, so a port match would only break");
            lines.add("        # things Sokar itself put here.");
            for (final String cidr : localV4) {
                lines.add("        ip daddr " + cidr + " accept");
            }
        }

        if (!gateEndpoints.isEmpty()) {
            lines.add("");
            lines.add("        # Sokar's own git gate, on this machine. Allowed for every security");
            lines.add("        # class including offline: the gate is why an offline project can");
            lines.add("        # still commit, and nothing here leaves the host.");
            for (final String endpoint : gateEndpoints) {
                lines.add("        ip daddr " + endpoint + " accept");
            }
        }

        if (securityClass != SecurityClass.OFFLINE) {
            if (!resolvers.isEmpty()) {
                lines.add("");
                lines.add("        # DNS goes to the resolver Sokar runs, and nowhere else.");
                for (final String resolver : resolvers) {
                    lines.add("        ip daddr " + resolver + " udp dport 53 accept");
                    lines.add("        ip daddr " + resolver + " tcp dport 53 accept");
                }
            }
            lines.add("");
            lines.add("        # Web ports only. The resolver and the clearance path both add a");
            lines.add("        # HOST here and neither can name a port, so accepting every port");
            lines.add("        # would grant more than either of them meant - git over ssh above");
            lines.add("        # all, which pushes past the gate. No udp: a client that cannot");
            lines.add("        # reach HTTP/3 falls back to TCP.");
            lines.add("        ip daddr @allowed_v4 tcp dport @allowed_ports accept");
            lines.add("        ip6 daddr @allowed_v6 tcp dport @allowed_ports accept");
        } else {
            lines.add("");
            lines.add("        # Security class 'offline': no egress set is consulted at all.");
        }

        lines.add("");
        lines.add("        # Everything that reaches this point is dropped by the chain policy.");
        lines.add("        # It is logged first, which is what the clearance prompt is built on.");
        lines.add("        log prefix \"" + DROP_PREFIX + " \" group " + NFLOG_GROUP);
        lines.add("    }");
        lines.add("}");

        return String.join("\n", lines) + "\n";
    }

    private static String join(Set<Integer> values) {
        return values.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String elements(Set<String> values) {
        if (values.isEmpty()) {
            return "        # (no entries)";
        }
        return "        elements = { " + String.join(", ", values) + " }";
    }
}
