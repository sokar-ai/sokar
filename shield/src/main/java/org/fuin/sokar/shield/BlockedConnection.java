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
