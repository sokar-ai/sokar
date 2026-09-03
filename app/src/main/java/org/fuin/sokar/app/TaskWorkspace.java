package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.gate.TaskToken;

/**
 * The git gate serving one task, and the settings a container needs to use it.
 * <p>
 * The agent gets a working copy cloned from Sokar's mirror and a token that only Sokar accepts.
 * It never sees the upstream and never holds a credential for it: a push reaches
 * {@code refs/sokar/incoming/}, where nothing reads it but the operator.
 * <p>
 * <strong>The gate binds to all interfaces, not loopback.</strong> A task container has its own
 * network namespace, so its loopback is not the host's - binding to 127.0.0.1 would make the gate
 * unreachable from the only thing that needs it. The firewall rule generated alongside is what
 * keeps that from being an opening: the container may reach this port and nothing else.
 */
public class TaskWorkspace {

    /** Where the working copy is mounted inside the container. */
    public static final String MOUNT = "/workspace";

    /**
     * Address a rootless container reaches the host on, as podman maps
     * {@link #containerVisibleHost()} in the container's {@code /etc/hosts}.
     */
    private static final String GATE_ADDRESS = "169.254.1.2";

    private final GitGate gate;

    private final TaskToken token;

    private final String host;

    private final int port;

    /**
     * Prepares the settings for one task's gate.
     * <p>
     * Does not start a server. The gate has to outlive {@code task run} - which either returns or
     * replaces itself with a shell - so it runs as its own process, and this only decides the port
     * and the token they will share.
     *
     * @param gate The project's gate.
     * @param host Address the container reaches the host on.
     */
    public TaskWorkspace(GitGate gate, String host) {
        this.gate = gate;
        this.host = host;
        this.token = TaskToken.mint();
        this.port = freePort();
        gate.initialise();
    }

    /**
     * Returns the port the gate will listen on.
     *
     * @return Port number.
     */
    public int port() {
        return port;
    }

    /**
     * Returns the token the gate and the container share.
     *
     * @return The task token.
     */
    public TaskToken token() {
        return token;
    }

    /**
     * Picks a free port by binding one and letting it go.
     * <p>
     * There is a race between this and the gate binding it, and it is accepted: the alternative is
     * starting the gate first and reading its port, which cannot be done before the firewall rule
     * that needs the port is written.
     */
    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException ex) {
            throw new org.fuin.sokar.gate.GateException("Cannot find a free port for the gate", ex);
        }
    }

    /**
     * Returns the URL the container clones from and pushes to.
     *
     * @param project The project.
     * @return Gate URL.
     */
    public String url(Project project) {
        return "http://" + host + ":" + port() + "/" + project.name() + ".git";
    }

    /**
     * Returns the environment a container needs to use the gate.
     * <p>
     * The token goes in through {@code GIT_CONFIG_*} rather than a file: a file in the image would
     * outlive the task, and a URL with credentials in it lands in every git error message.
     *
     * @param project The project.
     * @param taskName Name of the task, which is also the ref the agent pushes to.
     * @return Variables to set in the container.
     */
    public Map<String, String> environment(Project project, String taskName) {
        final String header = "Authorization: Basic " + Base64.getEncoder().encodeToString(
                ("sokar:" + token.value()).getBytes(StandardCharsets.UTF_8));
        final Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GIT_CONFIG_COUNT", "1");
        environment.put("GIT_CONFIG_KEY_0", "http.extraHeader");
        environment.put("GIT_CONFIG_VALUE_0", header);
        environment.put("SOKAR_GATE_URL", url(project));
        environment.put("SOKAR_TASK_REF", GitGate.INCOMING + taskName);
        return Map.copyOf(environment);
    }

    /**
     * Returns the shell that prepares the working copy inside the container.
     * <p>
     * Run as a command rather than baked into the image: the gate's port changes per task, and an
     * image carrying a URL would be wrong the moment it was reused.
     *
     * @return Command and arguments.
     */
    public java.util.List<String> cloneCommand() {
        return java.util.List.of("sh", "-c",
                "set -e; cd " + MOUNT + "; "
                        + "if [ ! -d .git ]; then git init -q -b main .; "
                        + "git remote add sokar \"$SOKAR_GATE_URL\"; fi; "
                        + "git fetch -q sokar 2>/dev/null || true; "
                        + "git config user.name \"${SOKAR_GIT_NAME:-agent}\"; "
                        + "git config user.email \"${SOKAR_GIT_EMAIL:-agent@localhost}\"");
    }

    /**
     * Returns the shell that pushes whatever the agent committed.
     *
     * @return Command and arguments.
     */
    public java.util.List<String> pushCommand() {
        return java.util.List.of("sh", "-c",
                "set -e; cd " + MOUNT + "; "
                        + "if [ -z \"$(git status --porcelain)\" ] && git rev-parse HEAD >/dev/null 2>&1; then "
                        + "  git push -q sokar HEAD:\"$SOKAR_TASK_REF\"; "
                        + "elif git rev-parse HEAD >/dev/null 2>&1; then "
                        + "  git add -A && git commit -q -m \"agent: uncommitted work\" "
                        + "  && git push -q sokar HEAD:\"$SOKAR_TASK_REF\"; "
                        + "else echo 'nothing to push'; fi");
    }

    /**
     * Returns the gate.
     *
     * @return The gate.
     */
    public GitGate gate() {
        return gate;
    }

    /**
     * Returns the address a rootless container reaches the host on.
     * <p>
     * Not the bridge gateway: with pasta, which podman uses by default for rootless containers,
     * the gateway is unreachable from inside and this name resolves to a link-local address that
     * is not. Measured rather than assumed - 10.88.0.1, 10.0.2.2 and the host's LAN address were
     * all unreachable from a container on this machine.
     *
     * @return Host name as seen from a container.
     */
    public static String containerVisibleHost() {
        return "host.containers.internal";
    }

    /**
     * Returns the address the firewall must allow for the gate.
     * <p>
     * A constant, not a lookup: {@link #containerVisibleHost()} is a name podman writes into the
     * container's {@code /etc/hosts}, so it does not resolve on the host at all. Resolving it here
     * threw {@link java.net.UnknownHostException} on every run, the gate rule was silently left
     * out of the ruleset, and every push from a task hung until it timed out.
     * <p>
     * Because this is assumed rather than discovered, {@link #verify(String)} checks it against
     * what the container actually sees, once the container is up.
     *
     * @return Address of the host as seen from inside a task container.
     */
    public static String gateAddress() {
        return GATE_ADDRESS;
    }

    /**
     * Checks the assumption behind {@link #gateAddress()} against a running container.
     *
     * @param hosts Content of the container's {@code /etc/hosts}.
     * @return {@code null} if the mapping is as expected, otherwise a message naming the
     *         difference.
     */
    public static String verify(String hosts) {
        for (final String line : hosts.split("\n")) {
            final String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            final String[] fields = entry.split("\\s+");
            for (int i = 1; i < fields.length; i++) {
                if (containerVisibleHost().equals(fields[i])) {
                    return GATE_ADDRESS.equals(fields[0]) ? null
                            : "the container reaches this host at " + fields[0] + ", not "
                                    + GATE_ADDRESS + "; the git gate is firewalled off";
                }
            }
        }
        return "the container has no " + containerVisibleHost()
                + " entry, so it cannot reach the git gate";
    }
}
