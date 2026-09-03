package org.fuin.sokar.shield;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

/**
 * Reads dropped packets from an NFLOG group and reports them.
 * <p>
 * Must run inside the container's network namespace, because NFLOG is per-namespace. It is started
 * by the reader hook, which enters the namespace with {@code nsenter} first.
 * <p>
 * <strong>Soft-fail by design.</strong> Losing this costs visibility, not containment: the
 * firewall is already loaded and keeps dropping whether anyone is listening or not.
 */
public class NflogReader implements AutoCloseable {

    /** Netfilter subsystem number for userspace logging. */
    private static final int NFNL_SUBSYS_ULOG = 4;

    /** Configuration message type. */
    private static final int NFULNL_MSG_CONFIG = 1;

    /** Attribute carrying a configuration command. */
    private static final int NFULA_CFG_CMD = 1;

    /** Command that binds this socket to a group. */
    private static final int NFULNL_CFG_CMD_BIND = 1;

    private static final int NLM_F_REQUEST = 1;

    private static final int NLM_F_ACK = 4;

    private static final int AF_INET = 2;

    private final NetlinkSocket socket;

    private volatile boolean running = true;

    /**
     * Opens a socket and binds it to an NFLOG group.
     *
     * @param group Group number, matching the {@code log ... group} rule.
     * @throws NetlinkException If the socket cannot be opened, or the kernel refuses the bind.
     */
    public NflogReader(int group) {
        socket = new NetlinkSocket();
        try {
            socket.send(bindMessage(group));
            final byte[] acknowledgement = socket.receive(4096);
            final int error = acknowledgement.length >= 20
                    ? ByteBuffer.wrap(acknowledgement).order(ByteOrder.nativeOrder()).getInt(16)
                    : -1;
            if (error != 0) {
                // Almost always no CAP_NET_ADMIN in the owning user namespace, which means the
                // reader was started outside the container's namespace.
                throw new NetlinkException("The kernel refused the NFLOG bind to group " + group,
                        Math.abs(error));
            }
        } catch (RuntimeException ex) {
            socket.close();
            throw ex;
        }
    }

    /**
     * Builds the {@code NFULNL_CFG_CMD_BIND} message.
     *
     * @param group Group to bind.
     * @return Message bytes.
     */
    static byte[] bindMessage(int group) {
        // nlmsghdr(16) + nfgenmsg(4) + one nlattr(8)
        final ByteBuffer buffer = ByteBuffer.allocate(28).order(ByteOrder.nativeOrder());
        buffer.putInt(28);
        buffer.putShort((short) ((NFNL_SUBSYS_ULOG << 8) | NFULNL_MSG_CONFIG));
        buffer.putShort((short) (NLM_F_REQUEST | NLM_F_ACK));
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.put((byte) AF_INET);
        buffer.put((byte) 0);
        // res_id carries the group number and is big endian, unlike everything around it.
        buffer.putShort(Short.reverseBytes((short) group));
        buffer.putShort((short) 8);
        buffer.putShort((short) NFULA_CFG_CMD);
        buffer.put((byte) NFULNL_CFG_CMD_BIND);
        buffer.put(new byte[3]);
        return buffer.array();
    }

    /**
     * Reads events until {@link #stop()} is called or the socket fails.
     *
     * @param consumer Receives every event, in the order the kernel produced it.
     */
    public void readUntilStopped(Consumer<BlockedConnection> consumer) {
        while (running) {
            final List<BlockedConnection> events;
            try {
                events = NflogMessage.parse(socket.receive(65536), Instant.now());
            } catch (NetlinkException ex) {
                if (running) {
                    throw ex;
                }
                return;
            }
            events.forEach(consumer);
        }
    }

    /**
     * Asks the read loop to stop after the current message.
     */
    public void stop() {
        running = false;
    }

    @Override
    public void close() {
        stop();
        socket.close();
    }
}
