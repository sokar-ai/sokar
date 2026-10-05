package org.fuin.sokar.shield;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.wire.Json;

/**
 * One connection the firewall refused.
 *
 * @param prefix Log prefix from the nftables rule, identifying which rule dropped it.
 * @param protocol IP protocol number, 6 for TCP and 17 for UDP.
 * @param destination Destination address, as text.
 * @param port Destination port, or zero when the protocol has none.
 * @param at When the event was read.
 */
public record BlockedConnection(String prefix, int protocol, String destination, int port, Instant at) {

    /** IP protocol number for TCP. */
    public static final int TCP = 6;

    /** IP protocol number for UDP. */
    public static final int UDP = 17;

    /**
     * Returns the protocol as text.
     *
     * @return {@code tcp}, {@code udp}, or the number.
     */
    public String protocolName() {
        return switch (protocol) {
            case TCP -> "tcp";
            case UDP -> "udp";
            default -> String.valueOf(protocol);
        };
    }

    /**
     * Returns the destination as it would be shown to an operator.
     *
     * @return Address and port.
     */
    public String describe() {
        return port == 0 ? destination : destination + ":" + port;
    }

    /**
     * Returns a key identifying the destination, for suppressing repeats.
     * <p>
     * An agent retrying a connection produces one event per attempt, and a build tool can produce
     * hundreds a second. Prompting for each would make the machine unusable.
     *
     * @return Deduplication key.
     */
    public String key() {
        return protocolName() + "/" + destination + "/" + port;
    }

    /**
     * Tells whether this event is worth putting in front of an operator.
     * <p>
     * A container's network namespace is noisy in ways that have nothing to do with the agent:
     * IPv6 router solicitations, multicast listener reports and mDNS all get dropped by the
     * default-deny chain and all produce events. Asking about those would bury the one prompt that
     * matters, and an operator who has learned to dismiss prompts is worse than no prompts.
     * <p>
     * Only unicast TCP and UDP survive: those are the connections an agent actually makes.
     *
     * @return {@code true} if an operator should be asked about it.
     */
    public boolean worthAsking() {
        if (protocol != TCP && protocol != UDP) {
            return false;
        }
        return !isMulticast() && !isLinkLocal();
    }

    private boolean isMulticast() {
        if (destination.contains(":")) {
            // ff00::/8
            return destination.toLowerCase().startsWith("ff");
        }
        final int first = firstOctet();
        // 224.0.0.0/4 and the 255.255.255.255 broadcast.
        return (first >= 224 && first <= 239) || destination.equals("255.255.255.255");
    }

    private boolean isLinkLocal() {
        if (destination.contains(":")) {
            // fe80::/10
            final String lower = destination.toLowerCase();
            return lower.startsWith("fe8") || lower.startsWith("fe9")
                    || lower.startsWith("fea") || lower.startsWith("feb");
        }
        return destination.startsWith("169.254.");
    }

    private int firstOctet() {
        final int dot = destination.indexOf('.');
        try {
            return dot < 0 ? -1 : Integer.parseInt(destination.substring(0, dot));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * Renders the event as JSON, for the line protocol between reader and hub.
     *
     * @return The document.
     */
    public String toJson() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("prefix", prefix);
        map.put("protocol", protocolName());
        map.put("destination", destination);
        map.put("port", Integer.valueOf(port));
        map.put("at", at.toString());
        return Json.write(map);
    }
}
