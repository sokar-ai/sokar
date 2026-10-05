package org.fuin.sokar.wire.varlink;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Calls a varlink method over a unix socket.
 */
public class VarlinkClient implements AutoCloseable {

    private final VarlinkConnection connection;

    /**
     * Connects to a varlink service.
     *
     * @param socket Path of the socket.
     * @throws VarlinkException If the socket cannot be reached.
     */
    public VarlinkClient(Path socket) {
        try {
            final SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socket));
            connection = new VarlinkConnection(channel);
        } catch (IOException ex) {
            throw new VarlinkException("Cannot connect to " + socket, ex);
        }
    }

    /**
     * Calls a method once and returns the reply.
     *
     * @param method Fully qualified method name.
     * @param parameters Call parameters.
     * @return The reply parameters.
     * @throws VarlinkException If the peer returns an error or closes early.
     */
    public Map<String, Object> call(String method, Map<String, Object> parameters) {
        try {
            send(method, parameters, false);
            final Map<String, Object> reply = connection.receive();
            if (reply == null) {
                throw new VarlinkException("The service closed the connection without replying");
            }
            return replyParameters(reply);
        } catch (IOException ex) {
            throw new VarlinkException("Call to " + method + " failed", ex);
        }
    }

    /**
     * Calls a method with {@code more} and hands each reply to a consumer until the stream ends
     * or the consumer asks to stop.
     *
     * @param method Fully qualified method name.
     * @param parameters Call parameters.
     * @param consumer Receives each reply; returning {@code false} stops the stream.
     * @throws VarlinkException If the peer returns an error.
     */
    public void callMore(String method, Map<String, Object> parameters,
            Predicate<Map<String, Object>> consumer) {
        try {
            send(method, parameters, true);
            while (true) {
                final Map<String, Object> reply = connection.receive();
                if (reply == null) {
                    // The peer closed mid-stream. Returning here would be indistinguishable from
                    // a stream that ended properly - a client would stop watching and go on
                    // showing what it last saw, which for a fleet view or a clearance prompt is
                    // worse than an error. A finished stream arrives as a reply without
                    // 'continues'; a socket that simply ends did not finish.
                    throw new VarlinkException("The service closed the stream of " + method
                            + " before it ended");
                }
                final Map<String, Object> values = replyParameters(reply);
                if (!consumer.test(values)) {
                    return;
                }
                if (!Boolean.TRUE.equals(reply.get("continues"))) {
                    // The last reply in a stream omits 'continues'. Treating its absence as
                    // "keep waiting" is how a client hangs at the end of a perfectly good stream.
                    return;
                }
            }
        } catch (IOException ex) {
            throw new VarlinkException("Streaming call to " + method + " failed", ex);
        }
    }

    private void send(String method, Map<String, Object> parameters, boolean more)
            throws IOException {
        final Map<String, Object> call = new LinkedHashMap<>();
        call.put("method", method);
        call.put("parameters", parameters);
        if (more) {
            call.put("more", Boolean.TRUE);
        }
        connection.send(call);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> replyParameters(Map<String, Object> reply) {
        if (reply.get("error") instanceof String error) {
            final Object parameters = reply.get("parameters");
            throw new VarlinkException(error,
                    parameters instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
        }
        final Object parameters = reply.get("parameters");
        return parameters instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @Override
    public void close() throws IOException {
        connection.close();
    }
}
