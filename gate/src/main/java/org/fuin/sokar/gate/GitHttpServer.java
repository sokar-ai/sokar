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
 * <strong>Bound to loopback while a task runs</strong>, so nothing on the operator's network can
 * reach it. A rootless container can still get in because Sokar starts its own containers with
 * {@code org.fuin.sokar.runtime.LoopbackMapping}, which points pasta's address for this host at
 * the host's loopback. Where podman cannot do that - slirp4netns - {@code task run} falls back to
 * binding every interface and says so. The per-task token every request must carry is the defense
 * that holds either way.
 */
public class GitHttpServer implements AutoCloseable {

    /** Services a client may ask for. Anything else is refused. */
    private static final Set<String> SERVICES = Set.of("git-upload-pack", "git-receive-pack");

    private final HttpServer server;

    private final Path mirror;

    private final @org.jspecify.annotations.Nullable String ref;

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
        this(address, mirror, token, git, null);
    }

    /**
     * Constructor for one task's gate, which may update exactly one ref.
     * <p>
     * <strong>Enforced here, not agreed with the agent.</strong> {@code receive-pack} accepts whatever
     * refs a push names, and a task that pushed to {@code refs/heads/main} moved the mirror's own branch:
     * the history the next task of the project clones from, and the base every review compares against.
     * A push to another task's incoming ref would have written work under that task's name. So every ref
     * a push would update is read before git sees the request, and anything but this task's own ref is
     * refused. Measured by Agent Frontend on 2026-09-29: a plain {@code git push sokar <branch>} landed
     * in {@code refs/heads/}.
     *
     * @param address Where to listen.
     * @param mirror The bare mirror.
     * @param token What a push must present.
     * @param git How git is run.
     * @param ref The one ref a push may update, or {@code null} for any ref under
     *            {@link GitGate#INCOMING} - never a branch.
     */
    public GitHttpServer(InetSocketAddress address, Path mirror, TaskToken token, GitProcess git,
            @org.jspecify.annotations.Nullable String ref) {
        this.ref = ref;
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
            if (!authorized(exchange)) {
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
            // To the gate's log, which the operator reads; the pushing agent sees only the 403.
            System.err.println("refused: " + ex.getMessage());
            exchange.sendResponseHeaders(403, -1);
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
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
        if ("git-receive-pack".equals(service)) {
            // A body that cannot be read here is refused, never passed on: what it would update is unknown.
            final String encoding = exchange.getRequestHeaders().getFirst("Content-Encoding");
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                throw new GateException("a push with a " + encoding + " body is refused, as its refs cannot be read");
            }
            for (final String updated : updatedRefs(request)) {
                if (!mayUpdate(updated)) {
                    throw new GateException("this gate takes a push to " + (ref == null ? GitGate.INCOMING + "<name>" : ref)
                            + " only, not to " + updated);
                }
            }
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

    private boolean mayUpdate(String updated) {
        // A task's own ref, and its rescue beside it: the rescue pushes through this same gate, and a gate that
        // took only the one ref refused it - every rescue failed, and the container was kept.
        return ref != null ? ref.equals(updated) || (ref + GitGate.RESCUED).equals(updated)
                : updated.startsWith(GitGate.INCOMING) && updated.length() > GitGate.INCOMING.length();
    }

    /**
     * Reads the refs a {@code receive-pack} request would update, from the commands at its start.
     * <p>
     * Each command is a pkt-line {@code <old> <new> <ref>}, the first followed by a NUL and the
     * capabilities, up to a flush packet; the pack follows. A {@code shallow} line may come first. Anything
     * else - a push certificate, a line that is not three fields - is refused rather than guessed at.
     *
     * @param request The request body.
     * @return The refs, in the order named.
     * @throws GateException If the commands cannot be read.
     */
    static List<String> updatedRefs(byte[] request) {
        final List<String> refs = new java.util.ArrayList<>();
        int at = 0;
        while (true) {
            if (at + 4 > request.length) {
                throw new GateException("a push whose commands end before their flush is refused");
            }
            final int length;
            try {
                length = Integer.parseInt(new String(request, at, 4, StandardCharsets.US_ASCII), 16);
            } catch (NumberFormatException ex) {
                throw new GateException("a push whose commands cannot be read is refused", ex);
            }
            if (length == 0) {
                return List.copyOf(refs);
            }
            if (length < 4 || at + length > request.length) {
                throw new GateException("a push whose commands cannot be read is refused");
            }
            String line = new String(request, at + 4, length - 4, StandardCharsets.UTF_8);
            at += length;
            final int nul = line.indexOf('\0');
            if (nul >= 0) {
                line = line.substring(0, nul);
            }
            line = line.strip();
            if (line.startsWith("shallow ")) {
                continue;
            }
            final String[] fields = line.split(" ");
            if (fields.length != 3) {
                throw new GateException("a push command that is not '<old> <new> <ref>' is refused: " + line);
            }
            refs.add(fields[2]);
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
