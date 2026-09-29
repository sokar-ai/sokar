package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
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
 */
@Command(name = "authorize",
        mixinStandardHelpOptions = true,
        description = "Grants an authorization once, in any browser, for this account's later tasks.")
public class VaultAuthorizeCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<name>",
            description = "The vault entry of kind '" + DeviceGrant.KIND + "' that names the service.")
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
        final DeviceGrant grant;
        try {
            grant = grantFor(context, name);
        } catch (IllegalArgumentException | VaultException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }
        try {
            final DeviceGrant.Started started = grant.start();
            out.println("open      " + started.link());
            out.println("code      " + started.userCode());
            out.println("waiting   up to " + started.expiresIn().toMinutes() + " minutes for a decision");
            out.flush();
            final DeviceGrant.Outcome outcome = grant.await(started, duration -> Thread.sleep(duration));
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
     * Returns the device flow for a vault entry that names a service.
     *
     * @param context Where the vault is.
     * @param name The entry.
     * @return The flow.
     * @throws IllegalArgumentException If the entry is not one of kind {@value DeviceGrant#KIND}, or lacks a setting.
     */
    public static DeviceGrant grantFor(SokarContext context, String name) {
        final VaultEntry entry = context.credentials().get(name);
        if (entry == null || !DeviceGrant.KIND.equals(entry.type())) {
            throw new IllegalArgumentException("'" + name + "' is not a vault entry of kind " + DeviceGrant.KIND
                    + "; store one with 'sokar vault put " + name + " --type " + DeviceGrant.KIND
                    + " --setting client_id=... --setting device_authorization_url=... --setting token_url=..."
                    + " --setting scopes=...'");
        }
        return new DeviceGrant(DeviceGrant.Client.of(entry.value(), entry.settings()),
                java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30))
                        .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build());
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
