package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.supervisor.CodeGrant;
import org.fuin.sokar.supervisor.DeviceGrant;
import org.fuin.sokar.supervisor.TokenPurchase;
import org.fuin.sokar.vault.VaultEntry;
import org.fuin.sokar.vault.VaultException;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Lets a person grant an authorization once, in any browser, for every later task of this account.
 * <p>
 * <strong>The device code flow</strong> (decided by the operator on 2026-09-29): this shows a link and a
 * code, the person decides wherever they are, and the refresh token that comes back is kept in this account's
 * vault, hidden, with who granted it and when. No task ever holds it: the broker spends it on the host.
 * <p>
 * <strong>The redirect flow</strong>, for a service that offers no device code: this shows a link, and the
 * browser's answer comes back to a port on this machine's loopback. A person elsewhere forwards that port
 * through the ssh connection they already hold; the command says which.
 */
@Command(name = "authorize",
        mixinStandardHelpOptions = true,
        description = "Grants an authorization once, in any browser, for this account's later tasks.")
public class VaultAuthorizeCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>",
            description = "The vault entry of kind '" + DeviceGrant.KIND + "' or '" + CodeGrant.KIND
                    + "' that names the service.")
    private String name;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Flow grant;
        try {
            grant = flowFor(context, name);
        } catch (IllegalArgumentException | VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }
        try {
            final Shown started = grant.start();
            out.println("open      " + started.link());
            if (!started.code().isEmpty()) {
                out.println("code      " + started.userCode());
            }
            if (started.port() > 0) {
                // The answer comes back to this machine's loopback; from elsewhere, the port has to follow.
                out.println("from elsewhere, forward the answer's port first:  ssh -L " + started.port()
                        + ":127.0.0.1:" + started.port() + " <this machine>");
            }
            out.println("waiting   up to " + started.expiresIn().toMinutes() + " minutes for a decision");
            out.flush();
            final DeviceGrant.Outcome outcome = grant.await();
            switch (outcome.state()) {
                case "granted" -> {
                    if (outcome.refreshToken() == null) {
                        // Refused at setup rather than discovered when a task dies an hour in.
                        err.println("sokar: the service granted no refresh token, so the grant would end within the"
                                + " hour; ask for a scope that permits one - often 'offline_access' - and authorize"
                                + " again");
                        err.flush();
                        return 1;
                    }
                    keep(context, name, outcome.refreshToken());
                    out.println("granted   " + name + ", kept in this account's vault for its later tasks");
                    out.flush();
                    return 0;
                }
                case "refused" -> {
                    err.println("sokar: the authorization was refused");
                    err.flush();
                    return 1;
                }
                default -> {
                    err.println("sokar: nobody decided in time; run it again when you can");
                    err.flush();
                    return 1;
                }
            }
        } catch (TokenPurchase.Refused ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 1;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return 130;
        } catch (VaultException ex) {
            err.println("sokar: the grant was given, and could not be kept: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }

    /**
     * What a person is shown: a link, and where a flow needs them, a code to type or a port to forward.
     *
     * @param link The link, whole.
     * @param code The code to type if the link does not carry it, or empty.
     * @param port The loopback port the answer comes back to, or 0.
     * @param expiresIn How long the decision is waited for.
     */
    public record Shown(String link, String code, int port, java.time.Duration expiresIn) {

        /**
         * Returns the code to type.
         *
         * @return The code.
         */
        public String userCode() {
            return code;
        }
    }

    /** One way a person grants an authorization: shown something, then decided. */
    public interface Flow {

        /**
         * Starts: what the person is to be shown.
         *
         * @return What to show.
         * @throws TokenPurchase.Refused If the service refuses or cannot be reached, or the answer has nowhere to land.
         */
        Shown start() throws TokenPurchase.Refused;

        /**
         * Waits until the person decided or the time ran out.
         *
         * @return What was decided.
         * @throws TokenPurchase.Refused If the service answers something the flow does not know, or cannot be reached.
         * @throws InterruptedException If interrupted while waiting.
         */
        DeviceGrant.Outcome await() throws TokenPurchase.Refused, InterruptedException;
    }

    /** How long a person has to answer a redirect, where no service says. */
    static final java.time.Duration REDIRECT_WAIT = java.time.Duration.ofMinutes(10);

    /**
     * Returns the flow for a vault entry that names a service: the device code where the entry is of that kind,
     * the redirect otherwise.
     *
     * @param context Where the vault is.
     * @param name The entry.
     * @return The flow.
     * @throws IllegalArgumentException If the entry is not of a kind a person grants, or lacks a setting.
     */
    public static Flow flowFor(SokarContext context, String name) {
        final VaultEntry entry = context.credentials().get(name);
        final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
        if (entry != null && DeviceGrant.KIND.equals(entry.type())) {
            final DeviceGrant grant = new DeviceGrant(DeviceGrant.Client.of(entry.value(), entry.settings()), http);
            return new Flow() {

                private DeviceGrant.@org.jspecify.annotations.Nullable Started started;

                @Override
                public Shown start() throws TokenPurchase.Refused {
                    final DeviceGrant.Started now = grant.start();
                    started = now;
                    return new Shown(now.link(), now.userCode(), 0, now.expiresIn());
                }

                @Override
                public DeviceGrant.Outcome await() throws TokenPurchase.Refused, InterruptedException {
                    return grant.await(java.util.Objects.requireNonNull(started), duration -> Thread.sleep(duration));
                }
            };
        }
        if (entry != null && CodeGrant.KIND.equals(entry.type())) {
            final CodeGrant grant = new CodeGrant(CodeGrant.Client.of(entry.value(), entry.settings()), http);
            return new Flow() {

                private CodeGrant.@org.jspecify.annotations.Nullable Started started;

                @Override
                public Shown start() throws TokenPurchase.Refused {
                    final CodeGrant.Started now = grant.start(REDIRECT_WAIT);
                    started = now;
                    return new Shown(now.link(), "", now.port(), now.expiresIn());
                }

                @Override
                public DeviceGrant.Outcome await() throws TokenPurchase.Refused, InterruptedException {
                    return grant.await(java.util.Objects.requireNonNull(started));
                }
            };
        }
        throw new IllegalArgumentException("'" + name + "' is not a vault entry of kind " + DeviceGrant.KIND + " or "
                + CodeGrant.KIND + "; store one with 'sokar vault put " + name + " --type " + DeviceGrant.KIND
                + " --setting client_id=... --setting device_authorization_url=... --setting token_url=..."
                + " --setting scopes=...', or with --type " + CodeGrant.KIND
                + " and --setting authorization_url=... in place of the device URL");
    }

    /**
     * Keeps a grant in the account's vault, hidden, with who granted it and when.
     * <p>
     * A task acting as a person is an audit fact, and the record outlives every task.
     *
     * @param context Where the vault is.
     * @param name The entry the grant is for.
     * @param refreshToken What the service granted.
     */
    public static void keep(SokarContext context, String name, String refreshToken) {
        final VaultEntry kept = new VaultEntry(refreshToken, "refresh-token",
                Map.of("granted_by", System.getProperty("user.name", "unknown"), "granted_at", Instant.now().toString()));
        context.vault().update(context.requirePassphrase(), entries -> {
            entries.put(TaskSecrets.GRANT_PREFIX + name, kept);
            return entries;
        });
    }
}
