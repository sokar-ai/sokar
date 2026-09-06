package org.fuin.sokar.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.sokar.wire.SocketContext;

/**
 * An ssh-agent that signs with keys the container can never read.
 * <p>
 * The socket is bind-mounted into the task container as {@code SSH_AUTH_SOCK}, so {@code ssh} and
 * {@code git} inside it authenticate normally. What crosses the boundary is a request to sign some
 * bytes and a signature coming back. The private key stays in this process, on the host.
 * <p>
 * Only the two requests that matter are implemented: list identities, and sign. Everything else is
 * answered with a failure rather than a guess - an agent protocol is not a place to be
 * accommodating, and adding a key over this socket is exactly what must not be possible.
 */
public class SshAgentServer implements AutoCloseable, Runnable {

    /** Client asks for the identities the agent holds. */
    static final byte SSH_AGENTC_REQUEST_IDENTITIES = 11;

    /** Agent answers with its identities. */
    static final byte SSH_AGENT_IDENTITIES_ANSWER = 12;

    /** Client asks for a signature. */
    static final byte SSH_AGENTC_SIGN_REQUEST = 13;

    /** Agent answers with a signature. */
    static final byte SSH_AGENT_SIGN_RESPONSE = 14;

    /** Agent refuses. */
    static final byte SSH_AGENT_FAILURE = 5;

    /** Largest message accepted from a client. */
    static final int MAX_MESSAGE = 256 * 1024;

    private final Path socketPath;

    private final List<SigningKey> keys;

    private final ServerSocketChannel channel;

    private volatile boolean running = true;

    /**
     * Binds the socket.
     *
     * @param socketPath Where to create the socket.
     * @param keys Keys the agent offers.
     * @throws VaultException If the socket cannot be created.
     */
    public SshAgentServer(Path socketPath, List<SigningKey> keys) {
        this.socketPath = socketPath;
        this.keys = List.copyOf(keys);
        try {
            Files.createDirectories(socketPath.toAbsolutePath().getParent());
            Files.deleteIfExists(socketPath);
            // The DIRECTORY is the access control, not the socket file. Measured: a rootless
            // container's agent user is a subordinate uid on the host, so it cannot open an
            // owner-only socket the host user owns - the connect fails outright, and the task
            // simply cannot sign. Podman's --userns=keep-id would fix that only by pinning the
            // agent's uid in the image, which Sokar deliberately does not do. So the socket is
            // permissive inside a 0700 directory, and only the one container it is mounted into
            // can see it at all. Same reasoning as the vault proxy's socket.
            Files.setPosixFilePermissions(socketPath.toAbsolutePath().getParent(),
                    PosixFilePermissions.fromString("rwx------"));
            // Labelled as it is created: SELinux checks connectto against the socket, not the file.
            channel = SocketContext.openUnixSocket();
            channel.bind(UnixDomainSocketAddress.of(socketPath));
            Files.setPosixFilePermissions(socketPath,
                    PosixFilePermissions.fromString("rw-rw-rw-"));
        } catch (IOException ex) {
            throw new VaultException("Cannot create the agent socket at " + socketPath, ex);
        }
    }

    /**
     * Returns the socket path.
     *
     * @return Path clients set as {@code SSH_AUTH_SOCK}.
     */
    public Path socketPath() {
        return socketPath;
    }

    @Override
    public void run() {
        while (running) {
            try {
                final SocketChannel client = channel.accept();
                // One virtual thread per client: a client that stalls mid-message must not stop
                // the agent answering anyone else.
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException ex) {
                if (running) {
                    throw new VaultException("The agent socket failed", ex);
                }
                return;
            }
        }
    }

    private void serve(SocketChannel client) {
        try (client) {
            while (running) {
                final byte[] request = readMessage(client);
                if (request == null) {
                    return;
                }
                write(client, handle(request));
            }
        } catch (IOException | RuntimeException ex) {
            // A broken or hostile client is not worth stopping the agent for.
        }
    }

    private byte[] readMessage(SocketChannel client) throws IOException {
        final ByteBuffer header = ByteBuffer.allocate(4);
        if (!readFully(client, header)) {
            return null;
        }
        final int length = header.flip().getInt();
        if (length <= 0 || length > MAX_MESSAGE) {
            throw new SshProtocolException("Implausible message length " + length);
        }
        final ByteBuffer body = ByteBuffer.allocate(length);
        if (!readFully(client, body)) {
            return null;
        }
        return body.flip().array();
    }

    private static boolean readFully(SocketChannel client, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            if (client.read(buffer) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Handles one request.
     *
     * @param request The message body, starting with the type byte.
     * @return The response body, starting with the type byte.
     */
    byte[] handle(byte[] request) {
        try {
            final ByteBuffer buffer = ByteBuffer.wrap(request);
            return switch (buffer.get()) {
                case SSH_AGENTC_REQUEST_IDENTITIES -> identities();
                case SSH_AGENTC_SIGN_REQUEST -> sign(buffer);
                default -> new byte[] { SSH_AGENT_FAILURE };
            };
        } catch (RuntimeException | IOException ex) {
            return new byte[] { SSH_AGENT_FAILURE };
        }
    }

    private byte[] identities() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SSH_AGENT_IDENTITIES_ANSWER);
        out.write(ByteBuffer.allocate(4).putInt(keys.size()).array());
        for (final SigningKey key : keys) {
            SshWire.writeString(out, key.keyBlob());
            SshWire.writeString(out, key.comment());
        }
        return out.toByteArray();
    }

    private byte[] sign(ByteBuffer buffer) throws IOException {

        final byte[] keyBlob = SshWire.readString(buffer);
        final byte[] data = SshWire.readString(buffer);

        for (final SigningKey key : keys) {
            if (java.util.Arrays.equals(key.keyBlob(), keyBlob)) {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.write(SSH_AGENT_SIGN_RESPONSE);
                SshWire.writeString(out, key.sign(data));
                return out.toByteArray();
            }
        }
        return new byte[] { SSH_AGENT_FAILURE };
    }

    private static void write(SocketChannel client, byte[] message) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(4 + message.length);
        buffer.putInt(message.length).put(message).flip();
        while (buffer.hasRemaining()) {
            client.write(buffer);
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            channel.close();
        } catch (IOException ex) {
            // Shutting down.
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ex) {
            // A leftover socket file is tidied by the next start, which unlinks first.
        }
    }

    /**
     * Returns the algorithm name used in messages, for tests.
     *
     * @return Algorithm name.
     */
    static String algorithm() {
        return new String("ssh-ed25519".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
