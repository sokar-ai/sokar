package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.supervisor.TokenExchange;
import org.fuin.sokar.supervisor.VaultProxy;
import org.fuin.sokar.vault.PhantomToken;
import org.fuin.sokar.vault.TokenBroker;
import org.fuin.sokar.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Serves the vault proxy for one task, on a unix socket the container reaches by bind mount.
 * <p>
 * <strong>This process mints the phantom token, not the caller.</strong> The token and the real
 * credential then live in the same place, which is what lets the credential be read per request
 * and never handed to anyone else - including the {@code task start} process that started this one.
 * The minted value is written to {@code --token-file} for the task to inject into the container.
 * <p>
 * Minting also fails fast: {@link TokenBroker#mint} refuses a scope the vault cannot honor, so a
 * missing credential is reported here, before the container starts, rather than surfacing inside
 * the agent as a provider authentication error.
 */
@Command(name = "serve",
        mixinStandardHelpOptions = true,
        description = "Serves the credential proxy for one task on a unix socket.")
public class VaultServeCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--socket", paramLabel = "<file>", required = true,
            description = "Unix socket to bind, created owner-only.")
    private Path socket;

    @Option(names = "--credential", paramLabel = "<name>", required = true,
            description = "Vault entry to swap in - the provider's name.")
    private String credential;

    @Option(names = "--task", paramLabel = "<name>",
            description = "Task the token is for. Default: ${DEFAULT-VALUE}")
    private String task = "shell";

    @Option(names = "--upstream", paramLabel = "<url>", required = true,
            description = "Real API endpoint requests are reissued to.")
    private String upstream;

    @Option(names = "--auth-header", paramLabel = "<name>",
            description = "Header the real credential goes in. Default: ${DEFAULT-VALUE}")
    private String authHeader = "Authorization";

    @Option(names = "--auth-prefix", paramLabel = "<text>",
            description = "Text before the credential, for example 'Bearer '.")
    private String authPrefix = "";

    @Option(names = "--auth-query", paramLabel = "<name>",
            description = "Query parameter the key goes in, for a service that takes it in the URL. Then no header carries it.")
    private @Nullable String authQuery;

    @Option(names = "--route", paramLabel = "<credential>=<upstream>|<header>|<prefix>|<query>",
            description = "Another credential this task holds, and where its requests go: its destination, the"
                    + " header its key goes in, the text before it, and the URL parameter instead of the header"
                    + " (empty for none). Repeatable. Each gets its own token, written to --route-tokens.")
    private Map<String, String> routeSpecs = new java.util.LinkedHashMap<>();

    @Option(names = "--route-tokens", paramLabel = "<dir>",
            description = "Where each --route's token is written, as <credential>.token, owner-only.")
    private @Nullable Path routeTokens;

    @Option(names = "--token-file", paramLabel = "<file>", required = true,
            description = "Writes the minted phantom token here, owner-only.")
    private Path tokenFile;

    @Option(names = "--reuse-token",
            description = "Adopts the token already in --token-file instead of minting a new one,"
                    + " for a task being resumed.")
    private boolean reuseToken;

    @Option(names = "--pid-file", paramLabel = "<file>", required = true,
            description = "Writes this process's id here, so the poststop hook can reap it.")
    private Path pidFile;

    @Option(names = "--hours", paramLabel = "<n>",
            description = "How long the token is accepted. Default: ${DEFAULT-VALUE}")
    private int hours = 8;

    @Option(names = "--seconds", paramLabel = "<n>",
            description = "Stop after this long. Zero means run until killed.")
    private int seconds;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Reads the token a previous run left, or {@code null} when there is none to reuse.
     *
     * @return Token value.
     */
    private @Nullable String readToken() {
        try {
            final String value = java.nio.file.Files.readString(tokenFile).strip();
            return value.isEmpty() ? null : value;
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final Map<String, String> credentials = new java.util.LinkedHashMap<>();
        final PhantomToken token;
        final TokenBroker broker;
        final Map<String, VaultProxy.Route> routes = new java.util.LinkedHashMap<>();
        final Map<String, PhantomToken> routeTokenValues = new java.util.LinkedHashMap<>();
        try {
            routeSpecs.forEach((name, spec) -> routes.put(name, route(name, spec)));
        } catch (IllegalArgumentException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }
        if (!routes.isEmpty() && routeTokens == null) {
            err.println("sokar: --route needs --route-tokens, the directory its tokens are written to");
            err.flush();
            return 2;
        }
        final Map<String, org.fuin.sokar.supervisor.TokenPurchase> purchases = new java.util.LinkedHashMap<>();
        try {
            final Map<String, org.fuin.sokar.vault.VaultEntry> entries = context.credentials();
            entries.forEach((key, entry) -> credentials.put(key, entry.value()));
            // A credential that has to be bought: the broker buys the token here, on the host, and keeps it
            // in memory. What it holds is the client secret, which never leaves this process.
            for (final String name : java.util.stream.Stream.concat(java.util.stream.Stream.of(credential),
                    routes.keySet().stream()).toList()) {
                final org.fuin.sokar.vault.VaultEntry entry = entries.get(name);
                if (entry != null && org.fuin.sokar.supervisor.TokenPurchase.KIND.equals(entry.type())) {
                    try {
                        purchases.put(name, new org.fuin.sokar.supervisor.TokenPurchase(
                                org.fuin.sokar.supervisor.TokenPurchase.Client.of(entry.value(), entry.settings()),
                                java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
                                java.time.Clock.systemUTC()));
                    } catch (IllegalArgumentException ex) {
                        err.println("sokar: credential '" + name + "': " + ex.getMessage());
                        err.flush();
                        return 2;
                    }
                }
            }
            broker = new TokenBroker(() -> credentials);
            // A resumed task holds a token from before, in a container environment that cannot be
            // changed. Minting a new one would look to the agent exactly like a bad credential.
            final String existing = reuseToken ? readToken() : null;
            token = existing == null
                    ? broker.mint(credential, task, Duration.ofHours(hours))
                    : broker.adopt(existing, credential, task, Duration.ofHours(hours));
            for (final String name : routes.keySet()) {
                final String before = reuseToken ? read(routeTokenFile(name)) : null;
                routeTokenValues.put(name, before == null ? broker.mint(name, task, Duration.ofHours(hours))
                        : broker.adopt(before, name, task, Duration.ofHours(hours)));
            }
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }

        final java.util.function.Consumer<String> requests = line -> {
            out.println("request   " + line);
            out.flush();
        };
        try (VaultProxy proxy = new VaultProxy(socket, upstream,
                exchange(broker, token, credentials, purchases), authHeader, authPrefix, authQuery, routes,
                requests)) {

            // The routes' tokens before the agent's own: the agent's is what a caller waits for.
            routeTokenValues.forEach((name, minted) -> writeOwnerOnly(routeTokenFile(name), minted.value(), err));
            writeOwnerOnly(tokenFile, token.value(), err);
            try {
                org.fuin.sokar.wire.HelperPid.record(pidFile);
            } catch (java.io.IOException ex) {
                err.println("sokar: cannot write " + pidFile + ": " + ex.getMessage());
                err.flush();
            }

            out.println("socket    " + socket);
            out.println("upstream  " + upstream);
            out.println("scope     " + credential + "/" + task);
            out.println(authQuery == null ? "header    " + authHeader : "query     " + authQuery);
            // The token itself is not printed. It goes in a 0600 file instead, because this
            // process's output is redirected to a log that outlives the task.
            out.println("token     " + token);
            routes.forEach((name, route) -> out.println("route     " + name + " -> " + route.upstream()));
            out.flush();

            final Thread serving = Thread.ofPlatform().start(proxy);
            if (seconds > 0) {
                serving.join(Duration.ofSeconds(seconds));
            } else {
                serving.join();
            }
        }
        return 0;
    }

    /**
     * Builds the exchange, naming the three outcomes the proxy answers differently.
     * <p>
     * The broker cannot distinguish "token unknown" from "credential gone", because both come
     * back as an empty optional - so the vault is consulted separately to decide which happened.
     * Getting this wrong would tell an operator to restart a task when the real fix is to unlock
     * the vault.
     */
    private static TokenExchange exchange(TokenBroker broker, PhantomToken token,
            Map<String, String> credentials, Map<String, org.fuin.sokar.supervisor.TokenPurchase> purchases) {

        // The grant names the credential its token was scoped to, which is what picks the route. A credential
        // that has to be bought is attached as the token bought with it, never as the secret it was bought with.
        return presented -> broker.exchange(presented, Instant.now())
                .<TokenExchange.Result>map(real -> {
                    final String scope = broker.issued(presented).map(PhantomToken::scope).orElse(null);
                    final org.fuin.sokar.supervisor.TokenPurchase purchase = scope == null ? null : purchases.get(scope);
                    if (purchase == null) {
                        return new TokenExchange.Granted(real, scope);
                    }
                    try {
                        return new TokenExchange.Granted(purchase.current(), scope);
                    } catch (org.fuin.sokar.supervisor.TokenPurchase.Refused ex) {
                        // Said as the authorization server's, not as a wrong key or an expired task token.
                        return new TokenExchange.Unavailable(java.util.Objects.requireNonNullElse(ex.getMessage(),
                                "the authorization server did not sell a token"));
                    }
                })
                .orElseGet(() -> {
                    if (token.matches(presented) && !credentials.containsKey(token.scope())) {
                        return new TokenExchange.Unavailable(
                                "the vault no longer holds a credential for this task");
                    }
                    // A token this broker issued that is no longer valid ran out of time; anything
                    // else was never ours. The agent cannot tell those apart, so the proxy does.
                    return broker.issued(presented)
                            .filter(issued -> !issued.validAt(Instant.now()))
                            .<TokenExchange.Result>map(issued ->
                                    new TokenExchange.Expired(issued.expiresAt()))
                            .orElseGet(TokenExchange.Rejected::new);
                });
    }

    private Path routeTokenFile(String name) {
        return java.util.Objects.requireNonNull(routeTokens, "--route-tokens").resolve(name + ".token");
    }

    private static @Nullable String read(Path file) {
        try {
            final String value = java.nio.file.Files.readString(file).strip();
            return value.isEmpty() ? null : value;
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    /**
     * Reads one {@code --route}: {@code <upstream>|<header>|<prefix>|<query>}, the last two possibly empty.
     *
     * @param name The credential.
     * @param spec What was given for it.
     * @return The route.
     * @throws IllegalArgumentException If it is not four fields with an https destination and a header.
     */
    static VaultProxy.Route route(String name, String spec) {
        final String[] fields = spec.split("\\|", -1);
        if (fields.length != 4 || fields[0].isBlank() || fields[1].isBlank()) {
            throw new IllegalArgumentException("--route " + name + " takes <upstream>|<header>|<prefix>|<query>, not '"
                    + spec + "'");
        }
        if (!fields[0].startsWith("https://")) {
            throw new IllegalArgumentException("--route " + name + ": the destination must be https, not '" + fields[0] + "'");
        }
        return new VaultProxy.Route(fields[0], fields[1], fields[2], fields[3].isEmpty() ? null : fields[3]);
    }

    private static void writeOwnerOnly(Path file, String content, PrintWriter err) {
        if (file == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(file, content,
                    java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.setPosixFilePermissions(file, java.util.Set.of(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (java.io.IOException ex) {
            err.println("sokar: cannot write " + file + ": " + ex.getMessage());
            err.flush();
        }
    }

    @Spec
    private CommandSpec spec;
}
