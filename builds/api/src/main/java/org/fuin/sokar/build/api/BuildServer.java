package org.fuin.sokar.build.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.fuin.sokar.wire.varlink.VarlinkServer;
import org.jspecify.annotations.Nullable;

/**
 * The reader's side of {@link BuildProtocol#INTERFACE}: binds the socket and answers Sokar with a {@link BuildReader}.
 */
public class BuildServer implements AutoCloseable {

    private final VarlinkServer server;

    /**
     * Binds the socket and registers the protocol.
     *
     * @param reader The reader to serve.
     * @param socket Where to create the socket.
     */
    public BuildServer(final BuildReader reader, final Path socket) {

        this.server = new VarlinkServer(socket, BuildProtocol.INTERFACE).describedBy(idl());

        server.method("Describe", (parameters, replies) -> {
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("protocolVersion", Integer.valueOf(BuildProtocol.VERSION));
            answer.put("forge", reader.forge());
            replies.last(answer);
        });

        server.method("Head", (parameters, replies) -> {
            final String branch = required(parameters, "branch");
            final String commit = answering(() -> reader.head(target(parameters), branch));
            replies.last(Map.of("commit", commit));
        });

        server.method("Look", (parameters, replies) -> {
            final String commit = required(parameters, "commit");
            final Build.Logs logs = logs(parameters);
            replies.last(answering(() -> reader.look(target(parameters), commit, logs)).asMap());
        });
    }

    /**
     * Returns the socket path.
     *
     * @return Path Sokar connects to.
     */
    public Path socketPath() {
        return server.socketPath();
    }

    /**
     * Serves until the process is stopped.
     */
    public void serve() {
        server.run();
    }

    @Override
    public void close() {
        server.close();
    }

    /**
     * Returns the interface description, as the jar carries it.
     *
     * @return The IDL.
     */
    static String idl() {
        try (InputStream in = BuildServer.class.getResourceAsStream(BuildProtocol.INTERFACE + ".varlink")) {
            if (in == null) {
                throw new IllegalStateException("The jar has no " + BuildProtocol.INTERFACE + ".varlink");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + BuildProtocol.INTERFACE + ".varlink", ex);
        }
    }

    /** A refusal travels as the protocol's error of that name, so Sokar tells a rate limit from a failed build. */
    private static <T> T answering(final java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (BuildRefused ex) {
            throw new VarlinkException(BuildProtocol.error(ex.reason()), Map.of("detail", ex.detail(),
                    "retryAfter", Long.valueOf(ex.retryAfterSeconds())));
        }
    }

    private static Target target(final Map<String, Object> parameters) {
        try {
            return new Target(text(parameters.get("upstream")), text(parameters.get("api")),
                    text(parameters.get("token")));
        } catch (IllegalArgumentException ex) {
            throw new VarlinkException("org.varlink.service.InvalidParameter", Map.of("parameter",
                    String.valueOf(ex.getMessage())));
        }
    }

    /** Absent, it is "failure": a Sokar that does not say wants what every Sokar got before. */
    private static Build.Logs logs(final Map<String, Object> parameters) {
        try {
            return parameters.get("logs") == null ? Build.Logs.FAILURE : Build.Logs.of(text(parameters.get("logs")));
        } catch (IllegalArgumentException ex) {
            throw new VarlinkException("org.varlink.service.InvalidParameter", Map.of("parameter", "logs"));
        }
    }

    private static String required(final Map<String, Object> parameters, final String name) {
        final String value = text(parameters.get(name));
        if (value.isBlank()) {
            throw new VarlinkException("org.varlink.service.InvalidParameter", Map.of("parameter", name));
        }
        return value;
    }

    private static String text(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
