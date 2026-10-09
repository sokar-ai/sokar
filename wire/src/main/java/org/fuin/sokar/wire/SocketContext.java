package org.fuin.sokar.wire;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Labels a unix socket so a task container is allowed to connect to it under SELinux.
 * <p>
 * <strong>The label goes on the socket, not on the file.</strong> SELinux checks {@code connectto}
 * against the context of the socket object, which by default is the context of whoever created it -
 * for any program an operator starts, {@code unconfined_t}, which containers may not reach. The
 * container runtime relabels the socket's directory entry and that is a different check; getting
 * only that one right still leaves the connection refused.
 * <p>
 * Measured on Fedora 44: without this the request never arrives, the proxy log stays empty, and the
 * agent reports an authentication failure.
 * <p>
 * Does nothing where there is no SELinux, and nothing where the policy module is not installed -
 * binding must not fail because a machine lacks a policy, or no task would run there at all.
 * {@link #available()} is what reports that, and {@code sokar doctor} asks it.
 */
public final class SocketContext implements Closeable {

    /** Type the policy module defines and grants containers {@code connectto} on. */
    public static final String TYPE = "sokar_socket_t";

    /** Where selinuxfs is mounted when SELinux runs; the attribute file below exists either way. */
    private static final Path SELINUXFS = Path.of("/sys/fs/selinux");

    /** Per-thread attribute deciding the context of sockets this thread creates. */
    private static final Path ATTRIBUTE = Path.of("/proc/thread-self/attr/sockcreate");

    /** Context of the current thread, from which the SELinux user is taken. */
    private static final Path CURRENT = Path.of("/proc/thread-self/attr/current");

    /** Clearing needs a byte written: an empty array performs no write and leaves it set. */
    private static final String CLEAR = "\0";

    private final boolean applied;

    private SocketContext(boolean applied) {
        this.applied = applied;
    }

    /**
     * Opens a unix socket channel carrying the label.
     * <p>
     * The label belongs to the socket, and the kernel assigns it when the socket is
     * <em>created</em> - not when it is bound. Wrapping the bind instead leaves the socket
     * unlabelled and the container's connection refused, with everything else looking correct.
     * Opening it here is what keeps that mistake from being possible at a call site.
     *
     * @return Channel, labeled where the policy allows it.
     * @throws IOException If the channel cannot be opened.
     */
    public static ServerSocketChannel openUnixSocket() throws IOException {
        try (SocketContext ignored = applied()) {
            return ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        }
    }

    /**
     * Applies the label to every socket this thread creates until the returned handle is closed.
     * <p>
     * The attribute is per-thread, so the socket must be created on the thread that called this.
     *
     * @return Handle that clears the label again.
     */
    static SocketContext applied() {
        try {
            write(socketContext());
            return new SocketContext(true);
        } catch (IOException ex) {
            // No SELinux, or no policy module. Both mean an unlabelled socket rather than none.
            return new SocketContext(false);
        }
    }

    /**
     * Tells whether this machine accepts the label, which is true only if the policy module is
     * installed.
     * <p>
     * Asking is the whole test: an unknown type is rejected outright, so nothing has to be started
     * and no privileges are needed.
     *
     * @return {@code true} if a socket bound now would carry the label.
     */
    public static boolean available() {
        if (!selinuxPresent()) {
            return false;
        }
        try {
            write(socketContext());
            write(CLEAR);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    /**
     * Tells whether this machine runs SELinux at all.
     * <p>
     * The attribute file exists whatever the security module is, so the filesystem is what
     * distinguishes them.
     *
     * @return {@code true} if SELinux is present.
     */
    public static boolean selinuxPresent() {
        return selinuxPresent(SELINUXFS);
    }

    /**
     * Tells whether SELinux is mounted at a place.
     *
     * @param selinuxfs Where selinuxfs is mounted when SELinux runs.
     * @return {@code true} if SELinux is present.
     */
    static boolean selinuxPresent(Path selinuxfs) {
        // The directory alone is no answer: a kernel with SELinux built in but not running keeps it as an empty
        // mount point. 'enforce' is there only when selinuxfs is mounted.
        return Files.isRegularFile(selinuxfs.resolve("enforce"));
    }

    /**
     * Returns the context a socket is labeled with.
     *
     * @return Full SELinux context.
     * @throws IOException If the thread's own context cannot be read.
     */
    static String socketContext() throws IOException {
        final String own = Files.readString(CURRENT).trim().replace("\0", "");
        final int user = own.indexOf(':');
        if (user < 1) {
            throw new IOException("Unrecognized process context: " + own);
        }
        // The SELinux user is carried over; only the type is Sokar's.
        return own.substring(0, user) + ":object_r:" + TYPE + ":s0";
    }

    private static void write(String value) throws IOException {
        try (FileOutputStream out = new FileOutputStream(ATTRIBUTE.toFile())) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Tells whether the label was actually applied.
     *
     * @return {@code true} if sockets created now carry it.
     */
    public boolean isApplied() {
        return applied;
    }

    @Override
    public void close() {
        if (!applied) {
            return;
        }
        try {
            write(CLEAR);
        } catch (IOException ex) {
            // Nothing useful can be done, and the process is about to serve one socket anyway.
        }
    }
}
