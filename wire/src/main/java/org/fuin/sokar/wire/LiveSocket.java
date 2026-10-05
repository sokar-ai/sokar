package org.fuin.sokar.wire;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Refuses to bind over a socket something else is still answering on.
 * <p>
 * <strong>Unlinking a socket does not stop the process holding it.</strong> The file is only a
 * name: removing it makes the process that bound it unreachable and leaves it running, serving
 * nobody, with nothing anywhere saying so. A second start therefore used to take the endpoint from
 * the first, and the one that disappeared was whoever was there already.
 * <p>
 * The test is a connection, because nothing else distinguishes the two cases. A socket whose
 * process is gone refuses the connection, and that one is stale and the caller's to remove; one
 * that accepts belongs to somebody still using it.
 * <p>
 * Here rather than in one server, because the sockets this matters most for are not the daemon's:
 * one carries credentials to a container and one signs with the operator's key.
 * <p>
 * <strong>This is not race-free, and the window is accepted rather than closed.</strong> The check
 * and the unlink are two operations: another process of the same user may bind the path in between,
 * after which the caller unlinks a live endpoint - exactly what the check exists to prevent, on a
 * different schedule. What the check buys is that a socket somebody is <em>already</em> answering on
 * is not taken, which is the case that happens by accident; what it does not buy is an ownership
 * guarantee.
 * <p>
 * Closing it takes an ownership protocol in which binding and replacing are one operation - a
 * per-run directory with an atomic rename, so no name is ever probed and then removed. That changes
 * how a task's runtime directory is laid out, which several other things depend on, and it was
 * deliberately not built. The residual risk is availability - a process left unreachable and
 * running - and not disclosure: nothing about this path hands a credential to anybody.
 * <p>
 * So do not describe the sequence below as safe. It is a refusal that covers the ordinary case.
 */
public final class LiveSocket {

    private LiveSocket() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Throws when something is listening on a path about to be bound.
     * <p>
     * A caller that unlinks after this returns is unlinking a path this method last saw a moment
     * ago, not one it holds - see the class comment for the window that leaves open.
     *
     * @param socket The path.
     * @param what What the caller is, for the message.
     * @throws IOException If something answers there.
     */
    public static void refuseToStealFrom(Path socket, String what) throws IOException {
        if (!Files.exists(socket)) {
            return;
        }
        try (SocketChannel probe = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
            throw new IOException("Another " + what + " is already listening on " + socket);
        } catch (IOException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Another ")) {
                throw ex;
            }
            // Refused, or not a socket at all. Nothing is being taken from anybody.
        }
    }
}
