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
import java.util.Map;
import java.util.Set;
import org.fuin.sokar.wire.SocketContext;
import org.jspecify.annotations.Nullable;

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

    /**
     * The media types the credential scan can actually read.
     * <p>
     * Everything else is refused rather than streamed. The scan looks for JSON member names in
     * decoded text, so an answer in a form it cannot read is one it cannot judge - and streaming
     * what cannot be judged is the same as judging it harmless. This is a fail-closed decision
     * with an availability cost, taken deliberately: a provider that starts answering in a form
     * not listed here breaks tasks visibly, where the alternative fails silently and in the
     * direction that loses a credential.
     */
    private static final Set<String> UNDERSTOOD_TYPES =
            Set.of("application/json", "text/event-stream");

    /**
     * Headers carrying a credential, all of which are dropped before forwarding.
     * <p>
     * Public, like the other bounds below, because {@code doc/reach.md} states them to the operator
     * and a test holds the two together: a bound that changes here fails the build until the document
     * says the same.
     */
    public static final Set<String> CREDENTIAL_HEADERS =
            Set.of("authorization", "x-api-key", "private-token", "proxy-authorization");

    /**
     * Headers that describe one connection rather than the message, plus the ones the client
     * library sets for itself. Forwarding these produces a request that contradicts the
     * connection actually being used.
     */
    private static final Set<String> HOP_BY_HOP = Set.of("connection", "keep-alive", "host",
            "transfer-encoding", "content-length", "upgrade", "te", "trailer",
            "proxy-connection", "expect");

    /** Largest request body accepted, in bytes - and so the most one request to the provider can carry out. */
    public static final int BODY_LIMIT = 32 * 1024 * 1024;

    /**
     * How much of an answer is read and examined before any of it is handed over; the rest is examined as it streams.
     * <p>
     * Visible because the adversarial tests place a credential field exactly across this boundary, and a copy of the
     * number in a test would be a second statement of one fact - the test would keep passing against a changed bound
     * while testing nothing.
     */
    public static final int PEEK = 8192;

    /**
     * Marks a request as asking the provider to mint or renew a credential.
     * <p>
     * <strong>Two grant types, one hazard.</strong> This began as {@code refresh_token} alone,
     * and the reason written beside the refusal - forwarding it attaches the real credential to a
     * request whose answer is a new one - is exactly as true of {@code client_credentials}. A
     * container asking for a token of its own is asking the one question the broker exists to
     * make unnecessary, and it would be answered with Sokar's credential rather than the task's.
     * <p>
     * <strong>What this does not refuse:</strong> the broker fetching a credential for a task by
     * client-credentials grant. That is the broker's own call to the authorization server, made
     * on this side of the socket, and it never passes through here.
     * <p>
     * Latent when written: no supported provider mints this way, so nothing reaches it today. It
     * was one pattern away from being reachable.
     */
    public static final List<String> REFUSED_GRANTS = List.of("refresh_token", "client_credentials");

    /** The grants above, as a request body names them. */
    private static final java.util.regex.Pattern MINTING_GRANT =
            java.util.regex.Pattern.compile(
                    "grant_type[\"'=:\\s]+(" + String.join("|", REFUSED_GRANTS) + ")");

    private final Path socket;

    private final String upstream;

    private final TokenExchange exchange;

    private final String authHeader;

    private final String authPrefix;

    private final @org.jspecify.annotations.Nullable String authQuery;

    private final Map<String, Route> routes;

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
        this(socket, upstream, exchange, authHeader, authPrefix, null, log);
    }

    /**
     * Constructor for a service that takes its key in the URL.
     * <p>
     * The container's request may carry its phantom token in that parameter; it is taken out, and the
     * real key is put into the same parameter of the request that goes upstream, and into no header.
     * Forwarded as it came, the phantom would have travelled to the provider beside the real one: one
     * request, two credentials, one of them wrong.
     *
     * @param socket Path to bind.
     * @param upstream Real API endpoint.
     * @param exchange Turns a presented token into a credential.
     * @param authHeader Header the credential belongs in, when it does not go in the URL.
     * @param authPrefix String placed before the credential in that header.
     * @param authQuery Query parameter the key goes in, or {@code null} for the header.
     * @param log Receives one line per request.
     */
    public VaultProxy(Path socket, String upstream, TokenExchange exchange, String authHeader,
            String authPrefix, @org.jspecify.annotations.Nullable String authQuery,
            java.util.function.Consumer<String> log) {
        this(socket, upstream, exchange, authHeader, authPrefix, authQuery, Map.of(), log);
    }

    /**
     * Where one credential's requests go, and where its key goes on them.
     *
     * @param upstream The destination.
     * @param authHeader The header the key goes in.
     * @param authPrefix Text before the key in that header.
     * @param authQuery The URL parameter the key goes in instead, or {@code null}.
     */
    public record Route(String upstream, String authHeader, String authPrefix,
            @org.jspecify.annotations.Nullable String authQuery) {

        /**
         * Constructor.
         *
         * @param upstream The destination.
         * @param authHeader The header.
         * @param authPrefix The prefix.
         * @param authQuery The URL parameter, or {@code null}.
         */
        public Route {
            upstream = upstream.endsWith("/") ? upstream.substring(0, upstream.length() - 1) : upstream;
            authQuery = authQuery == null || authQuery.isBlank() ? null : authQuery;
        }
    }

    /**
     * Constructor for a task that holds more than one credential.
     * <p>
     * <strong>The token picks the route, never the request.</strong> Each credential has its own phantom
     * token, scoped to it, and a request goes to the destination of the credential its token stands for,
     * with that credential's key where that destination expects it. A token presented with a path meant
     * for another service still goes to its own: a credential held for one service cannot be attached to
     * a request bound for another, whichever route the container asks for.
     *
     * @param socket Path to bind.
     * @param upstream The agent's own destination, for a token scoped to no other route.
     * @param exchange Turns a presented token into a credential and the scope it stands for.
     * @param authHeader The agent's own header.
     * @param authPrefix The agent's own prefix.
     * @param authQuery The agent's own URL parameter, or {@code null}.
     * @param routes The other credentials' routes, by the credential a token is scoped to.
     * @param log Receives one line per request.
     */
    public VaultProxy(Path socket, String upstream, TokenExchange exchange, String authHeader,
            String authPrefix, @org.jspecify.annotations.Nullable String authQuery, Map<String, Route> routes,
            java.util.function.Consumer<String> log) {

        this.routes = Map.copyOf(routes);
        this.authQuery = authQuery == null || authQuery.isBlank() ? null : authQuery;
        this.log = log;
        this.socket = socket;
        this.upstream = upstream.endsWith("/")
                ? upstream.substring(0, upstream.length() - 1) : upstream;
        this.exchange = exchange;
        this.authHeader = authHeader;
        this.authPrefix = authPrefix;
        // The authorities the account adds to the built-in ones, as every other client here.
        this.http = org.fuin.sokar.core.net.TrustedCertificates.builder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        try {
            // Never over a live one. This socket answers with a credential; replacing it on the
            // strength of a pathname would orphan whoever was serving and leave two processes
            // disagreeing about which of them the container is talking to.
            org.fuin.sokar.wire.LiveSocket.refuseToStealFrom(socket, "credential proxy");
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
            log.accept(head.method() + " " + Query.redacted(head.target()) + " -> 401 token not accepted");
            // Deliberately the provider's own vocabulary: an agent that gets this should behave
            // as it would for any rejected credential rather than treat it as a transport fault.
            out.write(HttpHead.response(401, "Unauthorized",
                    "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\","
                    + "\"message\":\"sokar: the presented token is not this task's token\"}}"));
            out.flush();
            return;
        }
        if (result instanceof TokenExchange.Expired expired) {
            log.accept(head.method() + " " + Query.redacted(head.target())
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
            log.accept(head.method() + " " + Query.redacted(head.target()) + " -> 503 " + unavailable.reason());
            out.write(HttpHead.response(503, "Service Unavailable", error("api_error",
                    "sokar: " + unavailable.reason())));
            out.flush();
            return;
        }
        final TokenExchange.Granted granted = (TokenExchange.Granted) result;
        final String real = granted.credential();
        final Route route = granted.scope() == null ? own() : routes.getOrDefault(granted.scope(), own());

        final Reading reading = assess(head.target(), body, head.value("content-encoding"));
        if (reading != Reading.CLEAN && reading != Reading.MINTS) {
            // Refused, never forwarded unread: a body this cannot read is one it cannot say carries no grant.
            final int status = reading == Reading.TOO_LARGE ? 413 : reading == Reading.UNSUPPORTED ? 415 : 400;
            final String why = reading == Reading.TOO_LARGE ? "the request body unpacks beyond what one request may carry"
                    : reading == Reading.UNSUPPORTED ? "the request body is coded in a way the broker does not read"
                    : "the request body says it is coded and does not decode";
            log.accept(head.method() + " " + Query.redacted(head.target()) + " -> " + status + " " + why);
            out.write(HttpHead.response(status, status == 413 ? "Content Too Large"
                    : status == 415 ? "Unsupported Media Type" : "Bad Request",
                    error("invalid_request_error", "sokar: " + why)));
            out.flush();
            return;
        }
        if (reading == Reading.MINTS) {
            // Refused here rather than upstream: forwarding it would attach the real credential
            // to a request whose answer is a new one, and the provider may rotate what Sokar
            // holds as a side effect of a question nobody wanted asked.
            log.accept(head.method() + " " + Query.redacted(head.target()) + " -> 403 token grant refused");
            out.write(HttpHead.response(403, "Forbidden",
                    "{\"type\":\"error\",\"error\":{\"type\":\"permission_error\","
                    + "\"message\":\"sokar: the broker holds this credential and buys or renews its tokens"
                    + " itself; it cannot be renewed or exchanged for another from inside the container."
                    + " The token the task holds is minted per task and ends with it\"}}"));
            out.flush();
            return;
        }

        final HttpRequest outbound;
        try {
            outbound = reissue(head, body, real, route);
        } catch (final IllegalArgumentException ex) {
            // Never the exception's own words, nor the target: either can carry what the request named.
            log.accept(head.method() + " -> 400 the request target is not a valid address");
            out.write(HttpHead.response(400, "Bad Request",
                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                    + "\"message\":\"sokar: the request target is not a valid address\"}}"));
            out.flush();
            return;
        }
        final HttpResponse<InputStream> response;
        try {
            response = http.send(outbound, HttpResponse.BodyHandlers.ofInputStream());
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

        log.accept(head.method() + " " + Query.redacted(head.target()) + " -> " + response.statusCode()
                + " from the provider");
        writeResponse(out, response);
    }

    /**
     * Returns an error answer in the provider's vocabulary, with a message that may carry text from elsewhere.
     *
     * @param type The error's type.
     * @param message What to say; quoted as JSON, so whatever it carries cannot break the answer.
     * @return The answer's body.
     */
    private static String error(final String type, final String message) {
        final Map<String, Object> inner = new java.util.LinkedHashMap<>();
        inner.put("type", type);
        inner.put("message", message);
        final Map<String, Object> outer = new java.util.LinkedHashMap<>();
        outer.put("type", "error");
        outer.put("error", inner);
        return org.fuin.sokar.wire.Json.write(outer);
    }

    /** What a request is, read for a token grant. */
    enum Reading {

        /** Nothing in it asks for a token: it goes on. */
        CLEAN,

        /** It asks the provider for a token: refused. */
        MINTS,

        /** Its body is coded in a way the broker does not read, or in more than one: refused, never forwarded unread. */
        UNSUPPORTED,

        /** Its body says it is coded and does not decode: refused. */
        UNREADABLE,

        /** Its body unpacks beyond the body limit: refused. */
        TOO_LARGE
    }

    /**
     * Reads a request for a token grant, in its target's query and in its whole body, decoded.
     * <p>
     * The query was not read, a compressed body was read to its first 8 KiB and forwarded whole, and a coding this did
     * not know was scanned as raw bytes and forwarded: three ways to send a grant past the refusal. Each is closed - a
     * body is decoded in full up to the limit a body has, and what cannot be read is refused rather than forwarded.
     *
     * @param target The request target, with its query.
     * @param body The request body, as it came.
     * @param coding Its {@code Content-Encoding}, or {@code null}.
     * @return What it is.
     */
    static Reading assess(final String target, final byte[] body, final @Nullable String coding) {
        final int query = target.indexOf('?');
        if (query >= 0 && names(target.substring(query + 1))) {
            return Reading.MINTS;
        }
        final java.util.List<String> codings = new java.util.ArrayList<>();
        if (coding != null) {
            for (final String each : coding.split(",")) {
                final String name = each.strip().toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && !name.equals("identity")) {
                    codings.add(name);
                }
            }
        }
        if (codings.size() > 1) {
            return Reading.UNSUPPORTED;
        }
        final byte[] plain;
        if (codings.isEmpty() || body.length == 0) {
            plain = body;
        } else {
            final String name = codings.get(0);
            try (InputStream in = switch (name) {
                case "gzip", "x-gzip" -> new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(body));
                case "deflate" -> new java.util.zip.InflaterInputStream(new java.io.ByteArrayInputStream(body));
                case "zstd" -> new io.airlift.compress.v3.zstd.ZstdInputStream(new java.io.ByteArrayInputStream(body));
                default -> null;
            }) {
                if (in == null) {
                    return Reading.UNSUPPORTED;
                }
                plain = in.readNBytes(BODY_LIMIT + 1);
            } catch (final IOException | RuntimeException ex) {
                return Reading.UNREADABLE;
            }
            if (plain.length > BODY_LIMIT) {
                return Reading.TOO_LARGE;
            }
        }
        return names(new String(plain, StandardCharsets.UTF_8)) ? Reading.MINTS : Reading.CLEAN;
    }

    /** Whether a text names a refused grant, as written, form-decoded or JSON-unescaped. */
    private static boolean names(final String text) {
        for (final String reading : List.of(text, formDecoded(text), jsonUnescaped(text))) {
            if (MINTING_GRANT.matcher(reading).find()) {
                return true;
            }
        }
        return false;
    }

    private static String formDecoded(final String text) {
        try {
            return java.net.URLDecoder.decode(text, StandardCharsets.UTF_8);
        } catch (final IllegalArgumentException ex) {
            // A stray % is not a form; the other readings still apply.
            return text;
        }
    }

    private static String jsonUnescaped(final String text) {
        final java.util.regex.Matcher escape = java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(text);
        return escape.replaceAll(found -> java.util.regex.Matcher.quoteReplacement(
                String.valueOf((char) Integer.parseInt(found.group(1), 16))));
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
    private String presented(HttpHead head) {
        // The route's own header first: a key in a header nobody else uses - x-goog-api-key - is still
        // this task's token, and refusing it said "not this task's token", which was false.
        final String declared = head.value(authHeader.toLowerCase(Locale.ROOT));
        if (declared != null && !declared.isBlank()) {
            return declared.contains(" ") ? declared.substring(declared.lastIndexOf(' ') + 1) : declared;
        }
        for (final Route route : routes.values()) {
            final String theirs = head.value(route.authHeader().toLowerCase(Locale.ROOT));
            if (theirs != null && !theirs.isBlank()) {
                return theirs.contains(" ") ? theirs.substring(theirs.lastIndexOf(' ') + 1) : theirs;
            }
        }
        for (final String parameter : queryParameters()) {
            final String inQuery = Query.value(head.target(), parameter);
            if (inQuery != null && !inQuery.isBlank()) {
                return inQuery;
            }
        }
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
        if (length == HttpHead.MALFORMED) {
            // Present and unusable. Treating it as absent forwards a request whose body the
            // caller declared and this did not send.
            throw new IOException("The request declares a Content-Length that is not a length");
        }
        if (length <= 0) {
            return new byte[0];
        }
        if (length > BODY_LIMIT) {
            throw new IOException("Request body exceeds " + BODY_LIMIT + " bytes");
        }
        return HttpHead.readExactly(in, (int) length);
    }

    private HttpRequest reissue(HttpHead head, byte[] body, String real, Route route) {

        // Every parameter any route carries a key in is taken out, so a phantom in another route's
        // parameter never travels; the real key goes back in only where this route says.
        final String target = Query.without(head.target(), queryParameters());
        // A path on the upstream and nothing else: appended as it came, '@other:<port>/x' made the upstream's host the
        // user part and '.attacker.example/x' extended it, and the real key went to a host the agent chose.
        if (!target.startsWith("/")) {
            throw new IllegalArgumentException("a request target that is not a path");
        }
        // Parsed before the key is added: a parser that refuses an address repeats it, key and all.
        final URI address = URI.create(route.upstream() + target);
        final URI upstream = URI.create(route.upstream());
        if (!java.util.Objects.equals(address.getHost(), upstream.getHost()) || address.getPort() != upstream.getPort()
                || address.getRawUserInfo() != null) {
            throw new IllegalArgumentException("a request target that leaves the upstream");
        }
        final HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(route.upstream() + (route.authQuery() == null ? target
                        : Query.replace(target, route.authQuery(), real))))
                .timeout(Duration.ofMinutes(10))
                .method(head.method(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));

        final java.util.Set<String> keyHeaders = new java.util.HashSet<>(CREDENTIAL_HEADERS);
        keyHeaders.add(authHeader.toLowerCase(Locale.ROOT));
        routes.values().forEach(each -> keyHeaders.add(each.authHeader().toLowerCase(Locale.ROOT)));
        for (int i = 0; i < head.names().size(); i++) {
            final String name = head.names().get(i).toLowerCase(Locale.ROOT);
            if (keyHeaders.contains(name) || HOP_BY_HOP.contains(name) || name.equals("accept-encoding")) {
                continue;
            }
            request.header(head.names().get(i), head.values().get(i));
        }
        // The scan reads text, so the answer is asked for uncompressed. An agent's own
        // 'gzip, deflate, br' got every answer compressed, and every one was withheld as unreadable.
        request.header("Accept-Encoding", "identity");
        if (route.authQuery() == null) {
            request.header(route.authHeader(), route.authPrefix() + real);
        }
        return request.build();
    }

    private Route own() {
        return new Route(upstream, authHeader, authPrefix, authQuery);
    }

    private java.util.Set<String> queryParameters() {
        final java.util.Set<String> parameters = new java.util.LinkedHashSet<>();
        if (authQuery != null) {
            parameters.add(authQuery);
        }
        routes.values().forEach(route -> {
            if (route.authQuery() != null) {
                parameters.add(route.authQuery());
            }
        });
        return parameters;
    }

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
        // Waits for the first bytes and then takes only what has already arrived. It waited for all PEEK of them: an
        // MCP server that sends one small event and then waits - a notification, a progress event - reached the agent
        // only once 8 KiB had piled up or the server closed (measured: 3 s held for a 3 s pause). Whatever comes after
        // is scanned chunk by chunk below, so a credential later in the answer still never crosses.
        while (filled < PEEK) {
            final int read = body.read(buffer, filled, PEEK - filled);
            if (read < 0) {
                break;
            }
            filled += read;
            if (body.available() <= 0) {
                break;
            }
        }
        return java.util.Arrays.copyOf(buffer, filled);
    }

    /**
     * Says why an answer cannot be checked for a credential, or {@code null} when it can.
     * <p>
     * Two cases, and both are blindness rather than suspicion. A body in any content coding but
     * {@code identity} reaches the scan as compressed bytes, in which a member name is simply not
     * present to be found. A media type outside {@link #UNDERSTOOD_TYPES} may frame a credential
     * in a way the scan does not look for at all.
     * <p>
     * An answer with no body is not refused: there is nothing in it to carry a credential, and
     * refusing one would break every {@code 204} and every redirect. That is decided on the bytes
     * that actually arrived rather than on a declared length - a chunked answer declares none, and
     * an empty one would have been refused for saying nothing about a body it does not have.
     *
     * @param response The upstream answer.
     * @param body How many bytes of body were read before judging.
     * @return What makes it unreadable, or {@code null}.
     */
    @org.jspecify.annotations.Nullable
    private static String whyUnreadable(HttpResponse<InputStream> response, int body) {
        final String coding = response.headers().firstValue("content-encoding")
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).orElse("identity");
        if (!"identity".equals(coding)) {
            return "content-encoding " + coding;
        }
        if (body == 0) {
            return null;
        }
        final String type = response.headers().firstValue("content-type").orElse("");
        final String media = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        if (media.isEmpty()) {
            return "no content-type";
        }
        return UNDERSTOOD_TYPES.contains(media) ? null : "content-type " + media;
    }

    /**
     * Streams the provider's answer back without buffering it.
     * <p>
     * Streaming rather than collecting, because a completion arrives as server-sent events over
     * a response that stays open: buffering it would hold the whole answer and deliver it at the
     * end, which reads to a user as the agent having hung.
     */
    private void writeResponse(OutputStream out, HttpResponse<InputStream> response)
            throws IOException {

        final InputStream upstreamBody = response.body();
        final CredentialScan scan = new CredentialScan();
        final byte[] first = peek(upstreamBody);
        final String unreadable = whyUnreadable(response, first.length);
        if (unreadable != null) {
            upstreamBody.close();
            log.accept("answer withheld: " + unreadable);
            out.write(HttpHead.response(502, "Bad Gateway", error("api_error",
                    "sokar: the provider answered in a form this proxy cannot check for credentials ("
                    + unreadable + "), so nothing was passed on")));
            out.flush();
            return;
        }
        if (scan.sees(first, first.length)) {
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
                if (scan.sees(buffer, read)) {
                    // The headers have already gone, so there is no status left to answer with:
                    // the only thing that still withholds the credential is to stop writing and
                    // drop the connection. The agent sees a truncated answer, which is what a
                    // failure looks like, and the credential does not cross.
                    //
                    // Scanning only the first few thousand bytes and streaming the rest unread
                    // was the earlier shape, and a credential after that point reached the
                    // container. A partial check on a stream is not a check.
                    log.accept("answer cut off: it carried a credential after the first bytes");
                    out.flush();
                    throw new IOException("The provider's answer carried a credential"
                            + " partway through; nothing further was passed on");
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
