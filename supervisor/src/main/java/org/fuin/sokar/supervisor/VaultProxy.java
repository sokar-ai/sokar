package org.fuin.sokar.supervisor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.fuin.sokar.wire.SocketContext;

/**
 * Turns a phantom token into a real credential, on the way out.
 * <p>
 * One proxy per task container, listening on a unix socket the container has bind-mounted. The
 * agent presents the phantom token it was given; the proxy checks it, <strong>removes every
 * credential header the agent sent</strong>, adds the real one, and reissues the request upstream
 * over TLS. The real credential is read per request and never handed to the container, so an agent
 * that leaks what it holds has leaked something that stops working when the task ends.
 * <p>
 * <strong>A socket, not a port.</strong> No firewall hole and no plaintext hop across the
 * namespace boundary; the filesystem decides who may connect, rather than a rule that has to be
 * got right.
 * <p>
 * <strong>The directory carries the access control, not the socket file.</strong> Measured: a
 * rootless container's unprivileged user is a subordinate uid on the host, so it cannot open a
 * {@code 0600} socket the host user owns - the connection fails outright. Podman's
 * {@code --userns=keep-id} would fix that, but only by pinning the agent's uid in the image, and
 * Sokar deliberately does not pin one because {@code 1000} is already taken on common bases (the
 * agent is {@code 1001} on {@code ubuntu:24.04}). So the socket itself is group- and
 * world-writable and its parent directory is {@code 0700}: no other host user can traverse to
 * it, and only the one container it is mounted into can see it at all.
 * <p>
 * This is only half the containment. The other half is a firewall rule denying the upstream host,
 * because an agent with a compiled-in base URL will otherwise ignore the socket and send the
 * phantom token straight to the provider. See {@link org.fuin.sokar.agent.api.ProviderRoute}.
 * <p>
 * One request per connection, answered with {@code Connection: close}. Keep-alive would buy
 * throughput that a single agent's request rate does not need, at the cost of connection state
 * this has no reason to hold.
 */
public class VaultProxy implements AutoCloseable, Runnable {

    /** Headers carrying a credential, all of which are dropped before forwarding. */
    private static final Set<String> CREDENTIAL_HEADERS =
            Set.of("authorization", "x-api-key", "private-token", "proxy-authorization");

    /**
     * Headers that describe one connection rather than the message, plus the ones the client
     * library sets for itself. Forwarding these produces a request that contradicts the
     * connection actually being used.
     */
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "host",
            "transfer-encoding", "content-length", "upgrade", "te", "trailer",
            "proxy-connection", "expect");

    /** Largest request body accepted, in bytes. */
    private static final int BODY_LIMIT = 32 * 1024 * 1024;

    /**
     * A credential in an answer, as a JSON field rather than as text.
     * <p>
     * The negative lookbehind is what keeps a model talking *about* OAuth from being mistaken
     * for a provider handing one over: inside a JSON string the quotes arrive escaped, so a
     * completion containing {@code \"refresh_token\":} does not match while a real token
     * response does.
     */
    private static final java.util.regex.Pattern CREDENTIAL_FIELD =
            java.util.regex.Pattern.compile(
                    "(?<!\\\\)\"(access_token|refresh_token|id_token)\"\\s*:");

    /** How much of an answer is examined before any of it is passed on, in bytes. */
    private static final int PEEK = 8192;

    /** Marks a request as asking the provider to renew a credential. */
    private static final java.util.regex.Pattern REFRESH_GRANT =
            java.util.regex.Pattern.compile("grant_type[\"'=:\\s]+refresh_token");

    private final Path socket;

    private final String upstream;

    private final TokenExchange exchange;

    private final String authHeader;

    private final String authPrefix;

    private final HttpClient http;

    private final ServerSocketChannel server;

    private final java.util.function.Consumer<String> log;

    private volatile boolean running = true;

    /**
     * Binds the socket. Nothing is served until {@link #run()} is called.
     *
     * @param socket Path to bind, created with owner-only permissions.
     * @param upstream Real API endpoint requests are reissued to.
     * @param exchange Turns the presented token into a credential, called once per request.
     * @param authHeader Header the real credential belongs in.
     * @param authPrefix String placed before the credential, for example {@code "Bearer "}.
     */
    public VaultProxy(Path socket, String upstream, TokenExchange exchange,
            String authHeader, String authPrefix) {
        this(socket, upstream, exchange, authHeader, authPrefix, line -> { });
    }

    /**
     * Constructor that also reports what passes through.
     * <p>
     * One line per request, without the token or the credential. Worth having: when an agent
     * fails to authenticate, the first question is whether it used the proxy at all, and that is
     * otherwise unanswerable from the outside - an agent that silently ignores the socket looks
     * exactly like one whose credential is wrong.
     *
     * @param socket Path to bind.
     * @param upstream Real API endpoint.
     * @param exchange Turns a presented token into a credential.
     * @param authHeader Header the credential belongs in.
     * @param authPrefix String placed before the credential.
     * @param log Receives one line per request.
     */
    public VaultProxy(Path socket, String upstream, TokenExchange exchange,
            String authHeader, String authPrefix, java.util.function.Consumer<String> log) {

        this.log = log;
        this.socket = socket;
        this.upstream = upstream.endsWith("/")
                ? upstream.substring(0, upstream.length() - 1) : upstream;
        this.exchange = exchange;
        this.authHeader = authHeader;
        this.authPrefix = authPrefix;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        try {
            Files.deleteIfExists(socket);
            Files.createDirectories(socket.getParent());
            // Locked down BEFORE the socket exists, because this is what keeps other host users
            // out. A window in which the directory is traversable is the whole hole.
            Files.setPosixFilePermissions(socket.getParent(),
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE));
            // Labelled as it is created: SELinux checks connectto against the socket, not the file.
            server = SocketContext.openUnixSocket();
            server.bind(UnixDomainSocketAddress.of(socket));
            // Deliberately permissive; see the class comment. The container's agent user is a
            // subordinate uid and cannot open an owner-only socket.
            Files.setPosixFilePermissions(socket, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE));
        } catch (IOException ex) {
            throw new SupervisorException("Could not bind the vault socket " + socket, ex);
        }
    }

    /**
     * Returns the socket being served.
     *
     * @return Socket path.
     */
    public Path socket() {
        return socket;
    }

    /**
     * Serves until closed. Each connection is handled on its own virtual thread, so one slow
     * upstream response does not stall the next request.
     */
    @Override
    public void run() {
        while (running) {
            final SocketChannel channel;
            try {
                channel = server.accept();
            } catch (IOException ex) {
                // Closing the server socket lands here, which is the normal way out.
                return;
            }
            Thread.ofVirtual().start(() -> {
                try (SocketChannel open = channel) {
                    handle(open);
                } catch (IOException ex) {
                    // A client that goes away mid-request is not this proxy's problem to solve,
                    // and must not take the accept loop down with it.
                    return;
                }
            });
        }
    }

    private void handle(SocketChannel channel) throws IOException {

        final InputStream in = new java.io.BufferedInputStream(Channels.newInputStream(channel));
        final OutputStream out = Channels.newOutputStream(channel);

        final HttpHead head = HttpHead.read(in);
        if (head == null) {
            return;
        }

        final byte[] body = body(head, in);

        final TokenExchange.Result result = exchange.exchange(presented(head));
        if (result instanceof TokenExchange.Rejected) {
            log.accept(head.method() + " " + head.target() + " -> 401 token not accepted");
            // Deliberately the provider's own vocabulary: an agent that gets this should behave
            // as it would for any rejected credential rather than treat it as a transport fault.
            out.write(HttpHead.response(401, "Unauthorized",
                    "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                    + "\"message\":\"sokar: the presented token is not this task's token\"}}"));
            out.flush();
            return;
        }
        if (result instanceof TokenExchange.Expired expired) {
            log.accept(head.method() + " " + head.target()
                    + " -> 401 this task's token expired at " + expired.when());
            // Says what happened rather than leaving the agent to report a wrong credential: the
            // token was right, the task simply outlived it.
            out.write(HttpHead.response(401, "Unauthorized",
                    "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                    + "\"message\":\"sokar: this task's token expired at " + expired.when()
                    + "; resume the task to issue it again, or start tasks with a longer"
                    + " --token-hours\"}}"));
            out.flush();
            return;
        }
        if (result instanceof TokenExchange.Unavailable unavailable) {
            log.accept(head.method() + " " + head.target() + " -> 503 " + unavailable.reason());
            out.write(HttpHead.response(503, "Service Unavailable",
                    "{\"type\":\"error\",\"error\":{\"type\":\"api_error\","
                    + "\"message\":\"sokar: " + unavailable.reason() + "\"}}"));
            out.flush();
            return;
        }
        final String real = ((TokenExchange.Granted) result).credential();

        if (body.length > 0 && REFRESH_GRANT.matcher(
                new String(body, StandardCharsets.UTF_8)).find()) {
            // Refused here rather than upstream: forwarding it would attach the real credential
            // to a request whose answer is a new one, and the provider may rotate what Sokar
            // holds as a side effect of a question nobody wanted asked.
            log.accept(head.method() + " " + head.target() + " -> 403 renewal refused");
            out.write(HttpHead.response(403, "Forbidden",
                    "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\","
                    + "\"message\":\"sokar: this task's credential cannot be renewed from"
                    + " inside the container; the token it holds is minted per task and ends"
                    + " with it\"}}"));
            out.flush();
            return;
        }

        final HttpResponse<InputStream> response;
        try {
            response = http.send(reissue(head, body, real),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            out.write(HttpHead.response(502, "Bad Gateway",
                    "{\"type\":\"error\",\"error\":{\"type\":\"api_error\","
                    + "\"message\":\"sokar: could not reach the provider\"}}"));
            out.flush();
            return;
        }

        log.accept(head.method() + " " + head.target() + " -> " + response.statusCode()
                + " from the provider");
        writeResponse(out, response);
    }

    /**
     * Returns the token the agent presented, from whichever credential header it used.
     * <p>
     * Every credential header is examined rather than only the one the route names: an agent may
     * follow its own convention, and a token in an unexpected header is still this task's token.
     * Any scheme prefix is stripped, so {@code Bearer x} and {@code x} both arrive as {@code x}.
     *
     * @param head The request head.
     * @return The presented token, or an empty string when none was sent.
     */
    private static String presented(HttpHead head) {
        for (final String name : CREDENTIAL_HEADERS) {
            final String value = head.value(name);
            if (value == null || value.isBlank()) {
                continue;
            }
            return value.contains(" ") ? value.substring(value.lastIndexOf(' ') + 1) : value;
        }
        return "";
    }

    private byte[] body(HttpHead head, InputStream in) throws IOException {
        if (head.chunked()) {
            return HttpHead.readChunked(in, BODY_LIMIT);
        }
        final long length = head.contentLength();
        if (length <= 0) {
            return new byte[0];
        }
        if (length > BODY_LIMIT) {
            throw new IOException("Request body exceeds " + BODY_LIMIT + " bytes");
        }
        return in.readNBytes((int) length);
    }

    private HttpRequest reissue(HttpHead head, byte[] body, String real) {

        final HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(upstream + head.target()))
                .timeout(Duration.ofMinutes(10))
                .method(head.method(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));

        for (int i = 0; i < head.names().size(); i++) {
            final String name = head.names().get(i).toLowerCase(Locale.ROOT);
            if (CREDENTIAL_HEADERS.contains(name) || HOP_BY_HOP.contains(name)
                    || name.equals(authHeader.toLowerCase(Locale.ROOT))) {
                continue;
            }
            request.header(head.names().get(i), head.values().get(i));
        }
        request.header(authHeader, authPrefix + real);
        return request.build();
    }

    /**
     * Streams the provider's answer back without buffering it.
     * <p>
     * Streaming rather than collecting, because a completion arrives as server-sent events over
     * a response that stays open: buffering it would hold the whole answer and deliver it at the
     * end, which reads to a user as the agent having hung.
     */
    /**
     * Says whether a response header may be repeated to an HTTP/1.1 client.
     * <p>
     * The upstream connection may be HTTP/2, whose pseudo-headers begin with a colon. Such a name
     * is illegal in HTTP/1.1, and a client that reads one rejects the whole response - which
     * surfaces as a connection error saying nothing about headers.
     *
     * @param name Header name from the upstream response.
     * @return {@code true} when it belongs in the response.
     */
    static boolean forwardable(String name) {
        return !name.startsWith(":") && !HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Reads the start of an answer, so it can be judged before any of it is handed over.
     * <p>
     * Bounded and then streamed: a completion arrives as a long stream of events, and buffering
     * all of it to look at the first line would make every answer wait for its last byte. A
     * credential response is small and says what it is within the first bytes.
     *
     * @param body The upstream body.
     * @return What was read, which is all there was when the answer is shorter than the bound.
     * @throws IOException If the stream fails.
     */
    private static byte[] peek(InputStream body) throws IOException {
        final byte[] buffer = new byte[PEEK];
        int filled = 0;
        while (filled < PEEK) {
            final int read = body.read(buffer, filled, PEEK - filled);
            if (read < 0) {
                break;
            }
            filled += read;
        }
        return java.util.Arrays.copyOf(buffer, filled);
    }

    private void writeResponse(OutputStream out, HttpResponse<InputStream> response)
            throws IOException {

        final InputStream upstreamBody = response.body();
        final byte[] first = peek(upstreamBody);
        if (CREDENTIAL_FIELD.matcher(new String(first, StandardCharsets.UTF_8)).find()) {
            // The one exchange whose answer is itself a credential. Measured with a stub
            // provider: the container received access_token and refresh_token verbatim, which
            // is the single thing the phantom token exists to prevent. What the provider did
            // upstream cannot be undone from here - it may already have rotated - so this stops
            // the answer rather than the act, and says so plainly.
            upstreamBody.close();
            log.accept("answer withheld: it carried a credential");
            out.write(HttpHead.response(403, "Forbidden",
                    "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\","
                    + "\"message\":\"sokar: the provider answered with a credential, which"
                    + " this container may not have; nothing was passed on\"}}"));
            out.flush();
            return;
        }

        final StringBuilder head = new StringBuilder("HTTP/1.1 ")
                .append(response.statusCode()).append(" \r\n");
        response.headers().map().forEach((name, values) -> {
            if (!forwardable(name)) {
                return;
            }
            for (final String value : values) {
                head.append(name).append(": ").append(value).append("\r\n");
            }
        });
        // Chunked, because the length is not known until the stream ends.
        head.append("Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        try (InputStream body = upstreamBody) {
            if (first.length > 0) {
                out.write((Integer.toHexString(first.length) + "\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                out.write(first);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = body.read(buffer)) != -1) {
                if (read == 0) {
                    continue;
                }
                out.write((Integer.toHexString(read) + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(buffer, 0, read);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
                // Flushed per chunk, or streamed events arrive in batches and the agent looks
                // slower than it is.
                out.flush();
            }
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /**
     * Returns the headers this proxy refuses to forward, for tests and documentation.
     *
     * @return Header names, lower case.
     */
    public static List<String> strippedHeaders() {
        return List.copyOf(CREDENTIAL_HEADERS);
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ex) {
            // Nothing useful to do while shutting down.
        }
        http.close();
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The socket lives in the runtime directory, which the kernel clears at session end.
        }
    }
}
