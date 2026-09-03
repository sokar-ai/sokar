package org.fuin.sokar.clearance.varlink;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves varlink methods over a unix socket.
 * <p>
 * Implements {@code org.varlink.service.GetInfo} itself, because that is what makes a service
 * introspectable by {@code varlinkctl} and by anything else that speaks the protocol - which in
 * turn is what makes an independent conformance check possible without writing one.
 */
public class VarlinkServer implements AutoCloseable, Runnable {

    /**
     * Handles one method call.
     */
    @FunctionalInterface
    public interface Method {

        /**
         * Runs the method.
         *
         * @param parameters Call parameters.
         * @param replies Where to send replies; call it more than once only for a streaming method.
         * @throws IOException If a reply cannot be sent.
         */
        void call(Map<String, Object> parameters, Replies replies) throws IOException;
    }

    /**
     * Sends replies for one call.
     */
    public interface Replies {

        /**
         * Sends a reply that is not the last one.
         *
         * @param parameters Reply parameters.
         * @throws IOException If sending fails.
         */
        void more(Map<String, Object> parameters) throws IOException;

        /**
         * Sends the final reply.
         *
         * @param parameters Reply parameters.
         * @throws IOException If sending fails.
         */
        void last(Map<String, Object> parameters) throws IOException;

        /**
         * Tells whether the caller asked for a stream.
         *
         * @return {@code true} if the call carried {@code more}.
         */
        boolean streaming();
    }

    private final Path socket;

    private final String interfaceName;

    private final Map<String, Method> methods = new ConcurrentHashMap<>();

    private final ServerSocketChannel channel;

    private volatile boolean running = true;

    /**
     * Binds the socket.
     *
     * @param socket Where to create it.
     * @param interfaceName Interface this service implements, for {@code GetInfo}.
     * @throws VarlinkException If the socket cannot be created.
     */
    public VarlinkServer(Path socket, String interfaceName) {
        this.socket = socket;
        this.interfaceName = interfaceName;
        try {
            Files.createDirectories(socket.toAbsolutePath().getParent());
            Files.deleteIfExists(socket);
            channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            channel.bind(UnixDomainSocketAddress.of(socket));
            Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rwx------"));
        } catch (IOException ex) {
            throw new VarlinkException("Cannot create the varlink socket at " + socket, ex);
        }
        methods.put("org.varlink.service.GetInfo", (parameters, replies) -> {
            final Map<String, Object> info = new LinkedHashMap<>();
            info.put("vendor", "fuin.org");
            info.put("product", "Sokar");
            info.put("version", "0.1.0");
            info.put("url", "https://github.com/fuinorg/sokar");
            info.put("interfaces", List.of("org.varlink.service", interfaceName));
            replies.last(info);
        });
    }

    /**
     * Registers a method.
     *
     * @param name Method name without the interface prefix.
     * @param method What to run.
     * @return This instance.
     */
    public VarlinkServer method(String name, Method method) {
        methods.put(interfaceName + "." + name, method);
        return this;
    }

    /**
     * Returns the socket path.
     *
     * @return Path.
     */
    public Path socketPath() {
        return socket;
    }

    @Override
    public void run() {
        while (running) {
            try {
                final SocketChannel client = channel.accept();
                Thread.ofVirtual().start(() -> serve(client));
            } catch (IOException ex) {
                if (running) {
                    throw new VarlinkException("The varlink socket failed", ex);
                }
                return;
            }
        }
    }

    private void serve(SocketChannel client) {
        try (VarlinkConnection connection = new VarlinkConnection(client)) {
            while (running) {
                final Map<String, Object> call = connection.receive();
                if (call == null) {
                    return;
                }
                handle(connection, call);
            }
        } catch (IOException | RuntimeException ex) {
            // One broken client does not take the service down.
        }
    }

    @SuppressWarnings("unchecked")
    private void handle(VarlinkConnection connection, Map<String, Object> call) throws IOException {

        final Object name = call.get("method");
        if (!(name instanceof String methodName)) {
            error(connection, "org.varlink.service.InvalidParameter", Map.of("parameter", "method"));
            return;
        }
        final Method method = methods.get(methodName);
        if (method == null) {
            error(connection, "org.varlink.service.MethodNotFound", Map.of("method", methodName));
            return;
        }

        final Object raw = call.get("parameters");
        final Map<String, Object> parameters =
                raw instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        final boolean more = Boolean.TRUE.equals(call.get("more"));

        try {
            method.call(parameters, new Replies() {

                @Override
                public void more(Map<String, Object> values) throws IOException {
                    final Map<String, Object> reply = new LinkedHashMap<>();
                    reply.put("parameters", values);
                    reply.put("continues", Boolean.TRUE);
                    connection.send(reply);
                }

                @Override
                public void last(Map<String, Object> values) throws IOException {
                    connection.send(Map.of("parameters", values));
                }

                @Override
                public boolean streaming() {
                    return more;
                }
            });
        } catch (VarlinkException ex) {
            error(connection, ex.getErrorName() == null
                    ? interfaceName + ".Failed" : ex.getErrorName(),
                    ex.getParameters() == null ? Map.of() : ex.getParameters());
        } catch (RuntimeException ex) {
            error(connection, interfaceName + ".Failed",
                    Map.of("message", String.valueOf(ex.getMessage())));
        }
    }

    private static void error(VarlinkConnection connection, String name,
            Map<String, Object> parameters) throws IOException {
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("error", name);
        message.put("parameters", parameters);
        connection.send(message);
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
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The next start unlinks first.
        }
    }
}
