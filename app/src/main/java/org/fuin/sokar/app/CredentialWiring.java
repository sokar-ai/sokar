package org.fuin.sokar.app;

import java.io.PrintWriter;
import org.jspecify.annotations.Nullable;

/**
 * The host-side processes that stand between a task and a real credential.
 * <p>
 * Split out of {@code TaskRunCommand} after {@link CredentialChoice}, which decides *which*
 * credential a task uses; this starts what serves it. The broker, the relay and the signing agent
 * are three processes with one property in common: the container talks to them, and none of them
 * ever hands over what it holds.
 * <p>
 * Each one it starts is recorded, because a task that is stopped and resumed has to bring the same
 * processes back in the same order - the socket a container has mounted is bound to the file that
 * existed when it started.
 */
final class CredentialWiring {

    /** Records a helper so a resumed task can start it again. */
    @FunctionalInterface
    interface Recorder {

        /**
         * Records one helper.
         *
         * @param name Helper name, which is also its pid and log file name.
         * @param command What to run.
         * @param environment What it needs in its environment.
         * @param phase Whether it must be up before the container or needs the running one.
         */
        void record(String name, java.util.List<String> command,
                java.util.Map<String, String> environment, String phase);
    }

    private final SokarContext context;

    private final CredentialChoice choice;

    private final Recorder recorder;

    private final String task;

    private final int tokenHours;

    private final @Nullable String upstream;

    /**
     * Constructor with what the run decided.
     *
     * @param context Where the paths and the vault come from.
     * @param choice Which credential this task uses.
     * @param recorder Where each started helper is recorded.
     * @param task Task name, which the token is minted for.
     * @param tokenHours How long that token lasts.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     */
    CredentialWiring(SokarContext context, CredentialChoice choice, Recorder recorder,
            String task, int tokenHours, @Nullable String upstream) {
        this.context = context;
        this.choice = choice;
        this.recorder = recorder;
        this.task = task;
        this.tokenHours = tokenHours;
        this.upstream = upstream;
    }

    private void record(String name, java.util.List<String> command,
            java.util.Map<String, String> environment, String phase) {
        recorder.record(name, command, environment, phase);
    }

    /**
     * Starts the relay that gives a URL agent something to dial inside its own namespace.
     * <p>
     * Only the listening end moves: the broker keeps the host's resolver, the host's egress and
     * the credential. Binding the broker itself in the namespace was tried and fails - it reads
     * the host's {@code /etc/resolv.conf} and then cannot resolve anything at all.
     *
     * @param runner Runs containers.
     * @param container Container name.
     * @param socket Broker socket to forward to.
     * @param out Where progress is reported.
     * @param err Where failures are reported.
     */
    void startRelay(TaskRunner runner, String container, java.nio.file.Path socket,
            PrintWriter out, PrintWriter err) {

        final java.util.Optional<Long> pid = runner.containerPid(container);
        if (pid.isEmpty()) {
            err.println("sokar: the container reports no process, so nothing can listen in its"
                    + " namespace and the agent has no endpoint");
            err.flush();
            return;
        }
        final java.nio.file.Path state = context.paths().containerState(container);
        final java.util.List<String> command = org.fuin.sokar.shield.EgressPolicy.inNamespace(
                pid.get(), java.util.List.of(
                        SokarBinary.path(),
                        "vault", "relay",
                        "--listen", String.valueOf(TaskWiring.VAULT_PORT),
                        "--socket", socket.toString(),
                        "--pid-file", state.resolve("relay.pid").toString()));
        try {
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("relay.log").toFile())
                    .start();
            record("relay", command, java.util.Map.of(), TaskHelpers.AFTER);
            out.println("endpoint  " + TaskWiring.VAULT_URL + " in the task's namespace");
            out.flush();
        } catch (java.io.IOException ex) {
            err.println("sokar: could not start the endpoint relay: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Returns where the agent was told to send its requests.
     * <p>
     * A socket agent already has the path in a variable, and repeating it here costs nothing. An
     * agent that can only address a URL has no variable to read it from, which is the whole reason
     * the endpoint is passed to the agent rather than left implicit.
     *
     * @param agent The agent.
     * @param environment What the container was given.
     * @return Endpoint, or empty when nothing was brokered.
     */
    String endpointFor(org.fuin.sokar.agent.api.InstalledAgent agent,
            java.util.Map<String, String> environment) {
        final SelectedProvider selection = choice.provider(agent);
        if (selection == null) {
            return "";
        }
        final var route = selection.route();
        if (route.endpoint() == org.fuin.sokar.agent.api.ProviderRoute.Endpoint.URL) {
            // The dialect's path belongs on the endpoint rather than in the agent: the same
            // provider serves different wire formats under different paths, and only the
            // provider knows which.
            return route.endpointFor(TaskWiring.VAULT_URL);
        }
        final String variable = route.socketEnvironment();
        return variable == null ? "" : environment.getOrDefault(variable, "");
    }

    /**
     * What the credential proxy set up for this task.
     *
     * @param socket Host path of the socket the container mounts.
     * @param upstreamHost Provider host to withhold from the firewall.
     * @param environment Variables the container needs to use the proxy.
     */
    record CredentialPlumbing(java.nio.file.Path socket, String upstreamHost,
            java.util.Map<String, String> environment) {
    }

    /**
     * Says why this task will run without a credential, or {@code null} when it will have one.
     * <p>
     * <strong>Locked is not the same as absent, and this used to say it was.</strong> A locked
     * vault reads as an empty set, so a credential that is sitting in the vault was reported as
     * missing - sending somebody to store one they already have, when what they have to do is
     * unlock it. The same conflation was in the daemon's {@code readable} field and is fixed
     * there; this is the other place it lived.
     *
     * @param stored What the vault holds, or empty when it cannot be read.
     * @param name Vault key this task's credential would be under.
     * @return The reason, or {@code null} when the credential is there.
     */
    @Nullable
    static String credentialUnavailable(
            java.util.Optional<java.util.Map<String, org.fuin.sokar.vault.VaultEntry>> stored,
            String name) {
        if (stored.isEmpty()) {
            return "the vault is locked, so this task cannot authenticate;"
                    + " 'sokar vault unlock' and start it again";
        }
        if (!stored.get().containsKey(name)) {
            return "the vault holds no credential for '" + name + "'";
        }
        return null;
    }

    /**
     * Says why an unattended run could not authenticate, or {@code null} when it could.
     * <p>
     * Only for a run nobody is watching. An unattended task that cannot authenticate is certain to
     * be wasted, and the person who finds the wreckage is not the one who started it - so this is
     * what lets the launcher refuse before anything is created rather than warn into an empty
     * room. The interactive modes keep the warning: somebody is right there and may be starting a
     * shell without caring whether the agent can authenticate at all.
     * <p>
     * An agent that takes no brokered credential answers {@code null}: there is nothing that could
     * be missing, and refusing it would stop a task that was never going to authenticate anyway.
     *
     * @param agent The agent, or {@code null} when none is installed.
     * @return The reason, or {@code null} when there is nothing in the way.
     */
    @Nullable
    String unavailableFor(org.fuin.sokar.agent.api.@Nullable InstalledAgent agent) {
        if (agent == null) {
            return null;
        }
        final SelectedProvider selection = choice.provider(agent);
        final org.fuin.sokar.agent.api.ProviderRoute route =
                selection == null ? null : selection.route();
        if (route == null || choice.tokenVariable(agent) == null) {
            return null;
        }
        return credentialUnavailable(context.readableCredentials(), choice.credentialName(agent));
    }

    @Nullable
    CredentialPlumbing startVault(org.fuin.sokar.agent.api.InstalledAgent agent,
            String container, PrintWriter out, PrintWriter err) {

        if (agent == null) {
            return null;
        }
        final SelectedProvider selection = choice.provider(agent);
        final org.fuin.sokar.agent.api.ProviderRoute route =
                selection == null ? null : selection.route();
        final String type = choice.credentialType(choice.credentialName(agent));
        final String variable = choice.tokenVariable(agent);
        if (route == null || variable == null) {
            // Nothing to proxy through. Not an error - an agent may take no credential at all -
            // but if it takes one and cannot be redirected, say so rather than issue a token
            // that cannot work.
            if (variable != null) {
                err.println("sokar: '" + agent.name() + "' declares no proxy route, so its"
                        + " credential cannot be brokered; it will not authenticate");
                err.flush();
            }
            return null;
        }
        final String unavailable = credentialUnavailable(context.readableCredentials(),
                choice.credentialName(agent));
        if (unavailable != null) {
            out.println("token     none - " + unavailable);
            return null;
        }

        final java.nio.file.Path state = context.paths().containerState(container);
        final java.nio.file.Path socket = state.resolve("vault.sock");
        final java.nio.file.Path tokenFile = state.resolve("vault.token");

        // A URL endpoint is bound inside the task's own network namespace: a host-side listener is
        // either unreachable from a rootless container or bound to every interface, and neither is
        // acceptable for something that answers with a credential. Entering the namespace is how
        // the ruleset and the resolver already get there.
        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                SokarBinary.path(),
                "vault", "serve",
                "--socket", socket.toString()));
        command.addAll(java.util.List.of(
                "--credential", choice.credentialName(agent),
                "--task", task,
                "--upstream", route.upstream(),
                "--auth-header", route.authHeaderFor(type),
                "--auth-prefix", route.authPrefixFor(type),
                "--token-file", tokenFile.toString(),
                "--pid-file", state.resolve("vault.pid").toString(),
                "--hours", String.valueOf(tokenHours)));

        try {
            java.nio.file.Files.deleteIfExists(tokenFile);
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("vault.log").toFile())
                    .start();
            record("vault", command, java.util.Map.of(),
                    TaskHelpers.BEFORE);
        } catch (java.io.IOException ex) {
            err.println("sokar: could not start the credential proxy: " + ex.getMessage());
            err.flush();
            return null;
        }

        final String token = awaitToken(socket, tokenFile);
        if (token == null) {
            err.println("sokar: the credential proxy did not come up, see "
                    + state.resolve("vault.log"));
            err.flush();
            return null;
        }

        final java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
        environment.put(variable, token);
        if (route.socketEnvironment() != null) {
            environment.put(route.socketEnvironment(), TaskWiring.VAULT_MOUNT);
        }
        final String baseUrl = agent.definition().provider() == null ? null
                : agent.definition().provider().baseUrlEnvironment();
        if (baseUrl != null) {
            // Both, always. The socket variable only picks the transport; without a base URL the
            // agent uses its own compiled-in endpoint and never touches the socket at all.
            environment.put(baseUrl, route.endpointFor(TaskWiring.VAULT_URL));
        }
        out.println("vault     " + socket + " -> " + route.upstream());
        out.println("token     " + variable + "=" + org.fuin.sokar.vault.PhantomToken.abbreviate(token));
        out.flush();
        return new CredentialPlumbing(socket, route.upstreamHost(), environment);
    }

    /**
     * Waits for the proxy to be listening and to have written its token.
     * <p>
     * Both, not either: the socket exists a moment before the token file does, and starting the
     * container with an empty token produces an authentication failure that looks like a bad
     * credential.
     *
     * @param socket Socket the proxy binds.
     * @param tokenFile File the proxy writes its token to.
     * @return The token, or {@code null} if it did not appear in time.
     */
    private static String awaitToken(java.nio.file.@org.jspecify.annotations.Nullable Path socket,
            java.nio.file.Path tokenFile) {
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                // A proxy that bound a port leaves no socket file to wait for; the token file is
                // written after it is listening either way, so that is the signal that works for
                // both.
                if ((socket == null || java.nio.file.Files.exists(socket))
                        && java.nio.file.Files.exists(tokenFile)) {
                    final String token = java.nio.file.Files.readString(tokenFile).strip();
                    if (!token.isEmpty()) {
                        return token;
                    }
                }
                Thread.sleep(100);
            } catch (java.io.IOException ex) {
                return null;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /**
     * Starts the ssh-agent for this task, detached, and returns the socket to mount.
     * <p>
     * Only for an online project, because only an online project pushes to a remote that wants a
     * key. The key itself stays in the vault: the container gets a socket that signs, so a leak
     * from inside the box yields nothing reusable.
     * <p>
     * Detached and pid-filed for the same reason as the gate and the credential proxy - it has to
     * outlive a {@code task start} that either returns or replaces itself with a shell, and the
     * poststop hook reaps every {@code *.pid} in the state directory.
     *
     * @param container Container name.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return Host path of the socket, or {@code null} if the agent could not be started.
     */
    java.nio.file.Path startSshAgent(String container, PrintWriter out, PrintWriter err) {

        final java.nio.file.Path state = context.paths().containerState(container);
        final java.nio.file.Path socket = state.resolve("ssh-agent.sock");
        final java.util.List<String> command = java.util.List.of(
                SokarBinary.path(),
                "vault", "agent",
                "--socket", socket.toString(),
                "--pid-file", state.resolve("ssh-agent.pid").toString());

        try {
            java.nio.file.Files.deleteIfExists(socket);
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("ssh-agent.log").toFile())
                    .start();
        } catch (java.io.IOException ex) {
            err.println("sokar: could not start the ssh-agent: " + ex.getMessage());
            err.flush();
            return null;
        }

        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (java.nio.file.Files.exists(socket)) {
                out.println("ssh       " + socket + " (signs without lending the key)");
                out.flush();
                return socket;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        err.println("sokar: the ssh-agent did not come up, see " + state.resolve("ssh-agent.log"));
        err.println("sokar: an online task cannot push without it;"
                + " store a key with 'sokar vault put ssh.default'");
        err.flush();
        return null;
    }
}
