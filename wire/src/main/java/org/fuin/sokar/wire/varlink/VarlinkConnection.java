package org.fuin.sokar.wire.varlink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.fuin.sokar.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * One varlink connection: JSON objects separated by a NUL byte.
 * <p>
 * That is the entire framing. No length prefixes, no handshake, no headers - which is why writing
 * this was preferable to depending on a Java varlink library, of which there is no maintained one.
 * <p>
 * Not thread-safe. A connection belongs to one caller.
 */
public class VarlinkConnection implements AutoCloseable {

    /** Largest message accepted from a peer. */
    static final int MAX_MESSAGE = 4 * 1024 * 1024;

    private final SocketChannel channel;

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    private final ByteBuffer reading = ByteBuffer.allocate(8192);

    /**
     * Constructor.
     *
     * @param channel The connected socket.
     */
    public VarlinkConnection(SocketChannel channel) {
        this.channel = channel;
    }

    /**
     * Sends one message.
     *
     * @param message The object to send.
     * @throws IOException If writing fails.
     */
    public void send(Map<String, Object> message) throws IOException {
        final byte[] payload = Json.write(message).getBytes(StandardCharsets.UTF_8);
        final ByteBuffer buffer = ByteBuffer.allocate(payload.length + 1);
        buffer.put(payload).put((byte) 0).flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * Reads one message.
     *
     * @return The object, or {@code null} when the peer closed the connection.
     * @throws IOException If reading fails.
     * @throws VarlinkException If the peer sent something that is not a varlink message.
     */
    @Nullable
    public Map<String, Object> receive() throws IOException {
        while (true) {
            final byte[] buffered = pending.toByteArray();
            for (int i = 0; i < buffered.length; i++) {
                if (buffered[i] == 0) {
                    final String text = new String(buffered, 0, i, StandardCharsets.UTF_8);
                    pending.reset();
                    pending.write(buffered, i + 1, buffered.length - i - 1);
                    return parse(text);
                }
            }
            if (pending.size() > MAX_MESSAGE) {
                // A peer that never sends a NUL is either broken or trying to make this process
                // allocate without limit.
                throw new VarlinkException("A varlink message exceeded " + MAX_MESSAGE + " bytes");
            }
            reading.clear();
            if (channel.read(reading) < 0) {
                return null;
            }
            reading.flip();
            pending.write(reading.array(), 0, reading.limit());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String text) {
        if (!(Json.parse(text) instanceof Map<?, ?> map)) {
            throw new VarlinkException("A varlink message must be a JSON object");
        }
        return (Map<String, Object>) map;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
