package org.fuin.sokar.gate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * The git smart-HTTP endpoint an agent pushes to.
 * <p>
 * Implements the two request shapes git actually uses and delegates the protocol itself to
 * {@code git upload-pack} and {@code git receive-pack}. Reimplementing the pack protocol would be
 * a great deal of code with a great deal of room to be subtly wrong; the git binaries are already
 * on the host and are the reference implementation.
 * <p>
 * <strong>Reachable from the operator's LAN while a task runs.</strong> It binds every interface,
 * because a loopback bind is unreachable from a rootless container here - measured. What keeps it
 * shut is the per-task token every request must carry, not the bind. Narrowing this needs pasta's
 * {@code --map-host-loopback}, which podman only accepts in a way that discards its own pasta
 * defaults and silently opens egress.
 */
public class GitHttpServer implements AutoCloseable {

    /** Services a client may ask for. Anything else is refused. */
    private static final Set<String> SERVICES = Set.of("git-upload-pack", "git-receive-pack");

    private final HttpServer server;

    private final Path mirror;

    private final TaskToken token;

    private final GitProcess git;

    /**
     * Runs a git subprocess for one request.
     */
    @FunctionalInterface
    public interface GitProcess {

        /**
         * Runs git and pipes the request body through it.
         *
         * @param arguments Arguments after {@code git}.
         * @param input Request body, possibly empty.
         * @return What git wrote to standard output.
         * @throws IOException If the process fails.
         */
        byte[] run(List<String> arguments, byte[] input) throws IOException;
    }

    /**
     * Binds the server.
     *
     * @param address Address and port to listen on.
     * @param mirror The bare mirror to serve.
     * @param token Token a client must present.
     * @param git Runs the git subprocesses.
     * @throws GateException If the address cannot be bound.
     */
    public GitHttpServer(InetSocketAddress address, Path mirror, TaskToken token, GitProcess git) {
        this.mirror = mirror;
        this.token = token;
        this.git = git;
        try {
            server = HttpServer.create(address, 0);
        } catch (IOException ex) {
            throw new GateException("Cannot listen on " + address, ex);
        }
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
    }

    /**
     * Starts serving.
     */
    public void start() {
        server.start();
    }

    /**
     * Returns the port actually bound, which matters when zero was requested.
     *
     * @return Port number.
     */
    public int port() {
        return server.getAddress().getPort();
    }

    void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorised(exchange)) {
                // The realm makes git prompt for credentials rather than simply failing, which is
                // what turns a wrong token into a usable message.
                exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"sokar\"");
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            final String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/info/refs")) {
                advertise(exchange);
            } else if ("POST".equals(exchange.getRequestMethod())) {
                service(exchange, path.substring(path.lastIndexOf('/') + 1));
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        } catch (GateException ex) {
            exchange.sendResponseHeaders(403, -1);
        } finally {
            exchange.close();
        }
    }

    private boolean authorised(HttpExchange exchange) {
        final String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Basic ")) {
            return false;
        }
        try {
            final String decoded = new String(
                    Base64.getDecoder().decode(header.substring("Basic ".length())),
                    StandardCharsets.UTF_8);
            final int colon = decoded.indexOf(':');
            return colon >= 0 && token.matches(decoded.substring(colon + 1));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private void advertise(HttpExchange exchange) throws IOException {

        final String query = exchange.getRequestURI().getQuery();
        final String service = query == null ? "" : query.replace("service=", "");
        if (!SERVICES.contains(service)) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        final byte[] refs = git.run(List.of(service.substring("git-".length()),
                "--stateless-rpc", "--advertise-refs", mirror.toString()), new byte[0]);

        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(packetLine("# service=" + service + "\n"));
        body.write("0000".getBytes(StandardCharsets.US_ASCII));
        body.write(refs);

        exchange.getResponseHeaders().add("Content-Type",
                "application/x-" + service + "-advertisement");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        send(exchange, body.toByteArray());
    }

    private void service(HttpExchange exchange, String service) throws IOException {

        if (!SERVICES.contains(service)) {
            exchange.sendResponseHeaders(403, -1);
            return;
        }

        final byte[] request;
        try (InputStream in = exchange.getRequestBody()) {
            request = in.readAllBytes();
        }

        final byte[] response = git.run(List.of(service.substring("git-".length()),
                "--stateless-rpc", mirror.toString()), request);

        exchange.getResponseHeaders().add("Content-Type", "application/x-" + service + "-result");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        send(exchange, response);
    }

    private static void send(HttpExchange exchange, byte[] body) throws IOException {
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Wraps text in a git pkt-line: a four-digit hex length that includes itself.
     *
     * @param text The line.
     * @return Encoded bytes.
     */
    static byte[] packetLine(String text) {
        final byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        final String length = String.format("%04x", payload.length + 4);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(length.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(payload);
        return out.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
