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
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Serves the vault proxy for one task, on a unix socket the container reaches by bind mount.
 * <p>
 * <strong>This process mints the phantom token, not the caller.</strong> The token and the real
 * credential then live in the same place, which is what lets the credential be read per request
 * and never handed to anyone else - including the {@code task run} process that started this one.
 * The minted value is written to {@code --token-file} for the task to inject into the container.
 * <p>
 * Minting also fails fast: {@link TokenBroker#mint} refuses a scope the vault cannot honour, so a
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

    @Option(names = "--agent", paramLabel = "<name>", required = true,
            description = "Agent whose credential is brokered. Also the token's scope.")
    private String agent;

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

    @Option(names = "--token-file", paramLabel = "<file>",
            description = "Writes the minted phantom token here, owner-only.")
    private Path tokenFile;

    @Option(names = "--pid-file", paramLabel = "<file>",
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

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final Map<String, String> credentials = new java.util.LinkedHashMap<>();
        final PhantomToken token;
        final TokenBroker broker;
        try {
            context.credentials().forEach((key, entry) -> credentials.put(key, entry.value()));
            broker = new TokenBroker(() -> credentials);
            token = broker.mint(agent, task, Duration.ofHours(hours));
        } catch (VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }

        try (VaultProxy proxy = new VaultProxy(socket, upstream,
                exchange(broker, token, credentials), authHeader, authPrefix, line -> {
                    out.println("request   " + line);
                    out.flush();
                })) {

            writeOwnerOnly(tokenFile, token.value(), err);
            writeOwnerOnly(pidFile, String.valueOf(ProcessHandle.current().pid()), err);

            out.println("socket    " + socket);
            out.println("upstream  " + upstream);
            out.println("scope     " + agent + "/" + task);
            out.println("header    " + authHeader);
            // The token itself is not printed. It goes in a 0600 file instead, because this
            // process's output is redirected to a log that outlives the task.
            out.println("token     " + token);
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
            Map<String, String> credentials) {

        return presented -> broker.exchange(presented, Instant.now())
                .<TokenExchange.Result>map(TokenExchange.Granted::new)
                .orElseGet(() -> token.matches(presented)
                        && !credentials.containsKey(token.scope())
                                ? new TokenExchange.Unavailable(
                                        "the vault no longer holds a credential for this task")
                                : new TokenExchange.Rejected());
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
