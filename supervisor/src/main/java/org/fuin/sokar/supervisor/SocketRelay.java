package org.fuin.sokar.supervisor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * Carries bytes from a port inside a task's network namespace to the broker's socket on the host.
 * <p>
 * <strong>Why this exists rather than binding the broker itself in the namespace.</strong> An
 * agent that can only be given a URL needs something listening on an address it can dial, and a
 * host-side listener is either unreachable from a rootless container or bound to every interface.
 * Putting the broker in the container's network namespace solves that and creates something worse:
 * the process keeps the host's filesystem, so it reads the host's {@code /etc/resolv.conf} and
 * tries a resolver that does not exist in that namespace - measured, and it fails as "could not
 * reach the provider". Its egress would also be governed by the task's own firewall.
 * <p>
 * A relay has neither problem. It resolves nothing and connects to nothing but a local file, so
 * the broker stays on the host with the host's DNS, the host's egress and the credential.
 */
public final class SocketRelay implements Runnable, AutoCloseable {

    private final ServerSocketChannel server;

    private final Path socket;

    private volatile boolean running = true;

    /**
     * Constructor.
     *
     * @param address Address to listen on, inside the task's namespace.
     * @param socket Broker socket on the host to forward to.
     */
    public SocketRelay(InetSocketAddress address, Path socket) {
        this.socket = socket;
        try {
            server = ServerSocketChannel.open();
            server.bind(address);
        } catch (IOException ex) {
            throw new SupervisorException("Could not bind the relay to " + address, ex);
        }
    }

    @Override
    public void run() {
        while (running) {
            final SocketChannel client;
            try {
                client = server.accept();
            } catch (IOException ex) {
                if (running) {
                    continue;
                }
                return;
            }
            // One virtual thread per connection, like the broker: an agent opens several and a
            // slow response on one must not stall the next.
            Thread.ofVirtual().start(() -> relay(client));
        }
    }

    private void relay(SocketChannel client) {
        try (client; SocketChannel upstream = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            upstream.connect(UnixDomainSocketAddress.of(socket));
            final Thread back = Thread.ofVirtual().start(() -> pump(upstream, client));
            pump(client, upstream);
            back.join();
        } catch (IOException | InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void pump(SocketChannel from, SocketChannel to) {
        final ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
        try {
            while (from.read(buffer) >= 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    to.write(buffer);
                }
                buffer.clear();
            }
        } catch (IOException ex) {
            // A closed connection is the normal end of a relayed request.
        }
        try {
            to.shutdownOutput();
        } catch (IOException ex) {
            // Already gone.
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ex) {
            // Nothing useful to do while shutting down.
        }
    }
}
