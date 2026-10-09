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
import picocli.CommandLine.Option;
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

    @Option(names = "--agent", paramLabel = "<agent>",
            description = "When several installed agents sign in to the provider, whose app is granted.")
    private @org.jspecify.annotations.Nullable String agentName;

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
        final int made = madeFromTheProvider(out, err);
        if (made != 0) {
            return made;
        }
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
                    final Kept kept = keepGranted(context, name, outcome);
                    if (!kept.kept()) {
                        err.println("sokar: " + kept.detail());
                        err.flush();
                        return 1;
                    }
                    out.println("granted   " + name + ", kept in this account's vault for its later tasks"
                            + (kept.detail().isEmpty() ? "" : "; " + kept.detail()));
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
     * Makes the entry for a provider nothing is stored for yet, from the provider's grant and the client id of the
     * agent that signs in, so a person types none of it. An entry that is there is used as it is.
     *
     * @param out Where the entry made is said.
     * @param err Where a refusal goes.
     * @return 0 when there is an entry to authorize, or when this is not a provider's name; otherwise an exit code.
     */
    private int madeFromTheProvider(final PrintWriter out, final PrintWriter err) {
        final org.fuin.sokar.agent.api.ProviderDefinition provider = context.providers().get(name);
        if (provider == null || provider.grant().isEmpty() || !context.vault().exists()) {
            return 0;
        }
        final java.util.Optional<org.fuin.sokar.vault.VaultFile.Opener> opener = context.openerAsking(false, err);
        if (opener.isEmpty() || context.vault().read(opener.get()).containsKey(name)) {
            return 0;
        }
        final java.util.List<org.fuin.sokar.agent.api.AgentDefinition> agents = new java.util.ArrayList<>();
        try (var installed = context.agents()) {
            for (final String agent : installed.names()) {
                installed.find(agent).ifPresent(found -> agents.add(found.definition()));
            }
        }
        final GrantEntry.Choice choice = GrantEntry.choose(provider, agents, agentName);
        if (choice.refusal() != null) {
            err.println("sokar: " + choice.refusal());
            err.flush();
            return 2;
        }
        final VaultEntry entry = java.util.Objects.requireNonNull(choice.entry());
        context.vault().update(opener.get(), entries -> {
            entries.put(name, entry);
            return entries;
        });
        out.println("entry     " + name + " (" + entry.type() + "), with the app " + choice.agent() + " signs in with: "
                + entry.settings().get("client_id")
                + (entry.settings().containsKey("client_owner") ? ", " + entry.settings().get("client_owner") : ""));
        out.flush();
        return 0;
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
        final java.net.http.HttpClient http = org.fuin.sokar.core.net.TrustedCertificates.builder(org.fuin.sokar.core.net.TrustedCertificates.file(context.paths().xdg()))
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
        keep(context, name, refreshToken, "refresh-token");
    }

    /**
     * Whether a grant was kept, and what to tell the person about it.
     *
     * @param kept Whether it is in the vault now.
     * @param detail Why not, or what to know about what was kept; "" for nothing.
     */
    public record Kept(boolean kept, String detail) {
    }

    /**
     * Keeps what a person granted, or says why it cannot be kept: one decision for the terminal and the daemon.
     * <p>
     * A refresh token is kept as the grant. A token that never expires and comes with nothing to renew it - GitHub's
     * device flow for an OAuth app grants exactly that - is kept itself and attached as it is. One that expires with
     * nothing to renew it is refused here rather than discovered when a task dies an hour in.
     *
     * @param context Where the vault is.
     * @param name The entry the grant is for.
     * @param outcome What the service granted.
     * @return Whether it was kept, and what to say.
     */
    public static Kept keepGranted(SokarContext context, String name, DeviceGrant.Outcome outcome) {
        if (outcome.refreshToken() == null && outcome.expiresIn() == null && outcome.accessToken() != null) {
            keep(context, name, outcome.accessToken(), HELD);
            return new Kept(true, "the service gave a token that does not expire, which removing it here cannot"
                    + " revoke");
        }
        if (outcome.refreshToken() == null) {
            return new Kept(false, "the service granted no refresh token, so the grant would end within the hour; ask"
                    + " for a scope that permits one - often 'offline_access' - and authorize again");
        }
        keep(context, name, outcome.refreshToken());
        return new Kept(true, "");
    }

    /**
     * What a kept grant's type is when it is an access token that never expires rather than a refresh token: the
     * broker attaches it as it is, and nothing here can revoke it at the service.
     */
    public static final String HELD = "access-token";

    /**
     * Keeps a grant in the account's vault, hidden, with who granted it and when.
     *
     * @param context Where the vault is.
     * @param name The entry the grant is for.
     * @param token What the service granted.
     * @param type {@code refresh-token}, or {@link #HELD} for an access token that never expires.
     */
    public static void keep(SokarContext context, String name, String token, String type) {
        final VaultEntry kept = new VaultEntry(token, type,
                Map.of("granted_by", System.getProperty("user.name", "unknown"), "granted_at", Instant.now().toString()));
        context.vault().update(context.requirePassphrase(), entries -> {
            entries.put(TaskSecrets.GRANT_PREFIX + name, kept);
            return entries;
        });
        // The question is answered: an interface showing it sees "granted" next.
        AuthorizationsNeeded.clear(context, name);
    }
}
