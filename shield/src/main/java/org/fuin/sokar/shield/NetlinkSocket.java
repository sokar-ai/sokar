package org.fuin.sokar.shield;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * An {@code AF_NETLINK} socket, opened through the Foreign Function &amp; Memory API.
 * <p>
 * The JDK has no netlink support and never has, which was once read as "Java cannot do this". It
 * is only true of {@code java.net}: {@code socket(2)} is four calls away through FFM, and message
 * parsing is plain byte work that {@link java.nio.ByteBuffer} already does.
 * <p>
 * Not thread-safe. One reader owns one socket.
 */
public class NetlinkSocket implements AutoCloseable {

    /** Netlink address family. */
    public static final int AF_NETLINK = 16;

    /** Raw socket type. */
    public static final int SOCK_RAW = 3;

    /** Netfilter netlink protocol. */
    public static final int NETLINK_NETFILTER = 12;

    private static final Linker LINKER = Linker.nativeLinker();

    private static final MemoryLayout ERRNO_LAYOUT = Linker.Option.captureStateLayout();

    private static final VarHandle ERRNO_HANDLE =
            ERRNO_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    private static final Linker.Option CAPTURE = Linker.Option.captureCallState("errno");

    private static final MethodHandle SOCKET = handle("socket", ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    private static final MethodHandle BIND = handle("bind", ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);

    private static final MethodHandle SEND = handle("send", ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);

    private static final MethodHandle RECV = handle("recv", ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT);

    private static final MethodHandle CLOSE =
            handle("close", ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    private static MethodHandle handle(String name, MemoryLayout returnType, MemoryLayout... arguments) {
        return LINKER.downcallHandle(
                LINKER.defaultLookup().find(name)
                        .orElseThrow(() -> new NetlinkException("Symbol '" + name + "' not found", 0)),
                FunctionDescriptor.of(returnType, arguments), CAPTURE);
    }

    private final Arena arena = Arena.ofShared();

    private final MemorySegment errno = arena.allocate(ERRNO_LAYOUT);

    private final int descriptor;

    private boolean closed;

    /**
     * Opens a netfilter netlink socket and binds it.
     *
     * @throws NetlinkException If the socket cannot be opened or bound.
     */
    public NetlinkSocket() {
        try {
            descriptor = (int) SOCKET.invokeExact(errno, AF_NETLINK, SOCK_RAW, NETLINK_NETFILTER);
            if (descriptor < 0) {
                throw new NetlinkException("socket(AF_NETLINK) failed", errorNumber());
            }
            // struct sockaddr_nl { u16 family; u16 pad; u32 pid; u32 groups; }
            final MemorySegment address = arena.allocate(12).fill((byte) 0);
            address.set(ValueLayout.JAVA_SHORT, 0, (short) AF_NETLINK);
            final int bound = (int) BIND.invokeExact(errno, descriptor, address, 12);
            if (bound < 0) {
                throw new NetlinkException("bind(AF_NETLINK) failed", errorNumber());
            }
        } catch (NetlinkException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new NetlinkException("Cannot open a netlink socket: " + ex, 0);
        }
    }

    /**
     * Sends a message.
     *
     * @param message Bytes to send.
     * @return Number of bytes sent.
     * @throws NetlinkException If the send fails.
     */
    public long send(byte[] message) {
        try (Arena call = Arena.ofConfined()) {
            final MemorySegment buffer = call.allocateFrom(ValueLayout.JAVA_BYTE, message);
            final long sent = (long) SEND.invokeExact(errno, descriptor, buffer,
                    (long) message.length, 0);
            if (sent < 0) {
                throw new NetlinkException("send() failed", errorNumber());
            }
            return sent;
        } catch (NetlinkException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new NetlinkException("Cannot send on a netlink socket: " + ex, 0);
        }
    }

    /**
     * Receives one message.
     *
     * @param capacity Maximum number of bytes to read.
     * @return The bytes received.
     * @throws NetlinkException If the receive fails.
     */
    public byte[] receive(int capacity) {
        try (Arena call = Arena.ofConfined()) {
            final MemorySegment buffer = call.allocate(capacity);
            final long read = (long) RECV.invokeExact(errno, descriptor, buffer, (long) capacity, 0);
            if (read < 0) {
                throw new NetlinkException("recv() failed", errorNumber());
            }
            return buffer.asSlice(0, read).toArray(ValueLayout.JAVA_BYTE);
        } catch (NetlinkException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new NetlinkException("Cannot receive on a netlink socket: " + ex, 0);
        }
    }

    private int errorNumber() {
        return (int) ERRNO_HANDLE.get(errno, 0L);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            final int ignored = (int) CLOSE.invokeExact(errno, descriptor);
        } catch (Throwable ex) {
            // Closing a descriptor that the kernel has already reclaimed is not worth an exception
            // on a shutdown path.
        } finally {
            arena.close();
        }
    }
}
