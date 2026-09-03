package org.fuin.sokar.shield;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Parses the netlink messages the kernel sends for {@code log group} rules.
 * <p>
 * Pure byte work, deliberately separated from the socket so it can be tested against captured
 * bytes instead of needing a kernel, a container and {@code CAP_NET_ADMIN}.
 * <p>
 * Netlink headers and attribute lengths are in host byte order; the netfilter payload inside them
 * is in network byte order. Mixing the two up is the classic way to get plausible nonsense out of
 * this parser, so the two are read through separate buffers.
 */
public final class NflogMessage {

    /** Length of {@code struct nlmsghdr}. */
    private static final int NLMSGHDR_LENGTH = 16;

    /** Length of {@code struct nfgenmsg}. */
    private static final int NFGENMSG_LENGTH = 4;

    /** Attribute holding the log prefix from the nftables rule. */
    private static final int NFULA_PREFIX = 10;

    /** Attribute holding the captured packet. */
    private static final int NFULA_PAYLOAD = 9;

    private NflogMessage() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Parses every event in one datagram.
     *
     * @param datagram Bytes as received from the socket.
     * @param at Time to stamp the events with.
     * @return Events, possibly empty.
     */
    public static List<BlockedConnection> parse(byte[] datagram, Instant at) {

        final List<BlockedConnection> events = new ArrayList<>();
        final ByteBuffer buffer = ByteBuffer.wrap(datagram).order(ByteOrder.nativeOrder());

        int offset = 0;
        while (offset + NLMSGHDR_LENGTH <= datagram.length) {

            final int length = buffer.getInt(offset);
            if (length < NLMSGHDR_LENGTH || offset + length > datagram.length) {
                break;
            }

            final int bodyStart = offset + NLMSGHDR_LENGTH + NFGENMSG_LENGTH;
            if (bodyStart <= offset + length) {
                parseOne(buffer, datagram, bodyStart, offset + length, at).ifPresent(events::add);
            }

            offset += align(length);
        }
        return List.copyOf(events);
    }

    private static Optional<BlockedConnection> parseOne(ByteBuffer buffer, byte[] datagram,
            int start, int end, Instant at) {

        String prefix = "";
        byte[] payload = null;

        int offset = start;
        while (offset + 4 <= end) {

            final int attributeLength = Short.toUnsignedInt(buffer.getShort(offset));
            final int type = Short.toUnsignedInt(buffer.getShort(offset + 2)) & 0x3fff;
            if (attributeLength < 4 || offset + attributeLength > end) {
                break;
            }

            final int valueStart = offset + 4;
            final int valueLength = attributeLength - 4;

            if (type == NFULA_PREFIX && valueLength > 0) {
                // The prefix is NUL-terminated inside the attribute.
                int textLength = valueLength;
                while (textLength > 0 && datagram[valueStart + textLength - 1] == 0) {
                    textLength--;
                }
                prefix = new String(datagram, valueStart, textLength, StandardCharsets.UTF_8);
            } else if (type == NFULA_PAYLOAD && valueLength > 0) {
                payload = java.util.Arrays.copyOfRange(datagram, valueStart, valueStart + valueLength);
            }

            offset += align(attributeLength);
        }

        return payload == null ? Optional.empty() : packet(prefix, payload, at);
    }

    private static Optional<BlockedConnection> packet(String prefix, byte[] payload, Instant at) {

        if (payload.length < 1) {
            return Optional.empty();
        }

        // The payload is the captured packet, so it is in network byte order throughout.
        final ByteBuffer packet = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
        final int version = (payload[0] & 0xf0) >> 4;

        final int protocol;
        final int headerLength;
        final byte[] address;

        if (version == 4) {
            if (payload.length < 20) {
                return Optional.empty();
            }
            headerLength = (payload[0] & 0x0f) * 4;
            protocol = payload[9] & 0xff;
            address = java.util.Arrays.copyOfRange(payload, 16, 20);
        } else if (version == 6) {
            if (payload.length < 40) {
                return Optional.empty();
            }
            headerLength = 40;
            protocol = payload[6] & 0xff;
            address = java.util.Arrays.copyOfRange(payload, 24, 40);
        } else {
            return Optional.empty();
        }

        final int port = port(packet, payload, headerLength, protocol);
        final String destination = text(address);
        return destination == null ? Optional.empty()
                : Optional.of(new BlockedConnection(prefix, protocol, destination, port, at));
    }

    private static int port(ByteBuffer packet, byte[] payload, int headerLength, int protocol) {
        final boolean hasPorts = protocol == BlockedConnection.TCP || protocol == BlockedConnection.UDP;
        if (!hasPorts || headerLength + 4 > payload.length) {
            return 0;
        }
        // Source port first, then destination.
        return Short.toUnsignedInt(packet.getShort(headerLength + 2));
    }

    @Nullable
    private static String text(byte[] address) {
        try {
            final InetAddress parsed = InetAddress.getByAddress(address);
            // getHostAddress must not trigger a reverse lookup; getByAddress never sets a host name,
            // so it does not.
            return parsed instanceof Inet4Address || parsed instanceof Inet6Address
                    ? parsed.getHostAddress() : null;
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static int align(int length) {
        return (length + 3) & ~3;
    }
}
