package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
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
 * <strong>The gate binds loopback.</strong> A task container has its own network namespace, so
 * its loopback is not the host's; what bridges the two is
 * {@link org.fuin.sokar.runtime.LoopbackMapping}, which Sokar applies to the containers it starts
 * itself. The firewall rule generated alongside narrows it further: the container may reach this
 * port on this address and nothing else.
 */
public class TaskWorkspace {

    /** Where the working copy is mounted inside the container. */
    public static final String MOUNT = "/workspace";

    /**
     * Address a rootless container reaches the host on, as podman maps
     * {@link #containerVisibleHost()} in the container's {@code /etc/hosts}.
     */
    private static final String GATE_ADDRESS = org.fuin.sokar.runtime.ContainerSpec.HOST_LOOPBACK;

    /** The gate, or {@code null} for an online project that pushes to its upstream directly. */
    private final @Nullable GitGate gate;

    /** The gate's token, {@code null} when there is no gate. */
    private final @Nullable TaskToken token;

    private final String host;

    /** The gate's port, zero when there is no gate. */
    private final int port;

    /** The upstream, set only when there is no gate. */
    private final @Nullable String upstream;

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
    private TaskWorkspace(@Nullable GitGate gate, String host,
            @Nullable String upstream) {
        this.gate = gate;
        this.host = host;
        this.upstream = upstream;
        this.token = gate == null ? null : TaskToken.mint();
        this.port = gate == null ? 0 : freePort();
        if (gate != null) {
            gate.initialize();
        }
    }

    /**
     * A workspace whose remote is the gate on this machine.
     * <p>
     * What {@code offline} and {@code guarded} projects get: the agent never learns the upstream
     * URL, and nothing it pushes leaves the machine until an operator approves it.
     *
     * @param gate The project's gate.
     * @param host Address the container reaches the host on.
     * @return A gated workspace.
     */
    public static TaskWorkspace gated(GitGate gate, String host) {
        return new TaskWorkspace(gate, host, null);
    }

    /**
     * A workspace whose remote is the project's real upstream.
     * <p>
     * What an {@code online} project gets, and the whole of what that class means: the gate is out
     * of the path, the agent clones from and pushes to the upstream itself, and there is no review
     * step. The container therefore needs credentials for that remote, which is the cost of the
     * class.
     *
     * @param upstream Upstream repository URL.
     * @return A direct workspace.
     */
    public static TaskWorkspace direct(String upstream) {
        return new TaskWorkspace(null, containerVisibleHost(), upstream);
    }

    /**
     * Returns whether the gate is in the path.
     *
     * @return {@code true} when the agent pushes to the gate rather than to the upstream.
     */
    public boolean gated() {
        return gate != null;
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
        return gate == null ? upstream
                : "http://" + host + ":" + port() + "/" + project.name() + ".git";
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
        final Map<String, String> environment = new LinkedHashMap<>();
        if (token != null) {
            final String header = "Authorization: Basic " + Base64.getEncoder().encodeToString(
                    ("sokar:" + token.value()).getBytes(StandardCharsets.UTF_8));
            environment.put("GIT_CONFIG_COUNT", "1");
            environment.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            environment.put("GIT_CONFIG_VALUE_0", header);
        }
        environment.put("SOKAR_REMOTE_URL", url(project));
        // A gated push goes to a ref no branch points at, so nothing an operator is reading moves
        // underneath them. A direct push has no review step and so goes to a real branch.
        environment.put("SOKAR_TASK_REF",
                gate == null ? "refs/heads/" + taskName : GitGate.INCOMING + taskName);
        return Map.copyOf(environment);
    }

    /**
     * Returns the shell that prepares the working copy inside the container.
     * <p>
     * Run as a command rather than baked into the image: the gate's port changes per task, and an
     * image carrying a URL would be wrong the moment it was reused.
     * <p>
     * <strong>Fetching is not checking out.</strong> This used to stop after the fetch, which
     * filled {@code .git} and left the working tree empty - so every task began in a directory
     * holding nothing but {@code .git}, and an agent asked to change a project could not see one
     * file of it. Reported from a machine where {@code ls -alF} in the workspace showed exactly
     * that.
     * <p>
     * <strong>Only when there is no {@code HEAD} yet.</strong> The checkout is guarded on an
     * unborn branch rather than on an empty directory, because the same command runs again when a
     * task is resumed: a workspace with commits in it, or with work the agent has not committed,
     * must not be reset to what the mirror holds. An empty mirror leaves {@code HEAD} unborn and
     * the workspace empty, which is right - that is a project with no history yet.
     *
     * @return Command and arguments.
     */
    public java.util.List<String> cloneCommand() {
        return java.util.List.of("sh", "-c", cloneScript());
    }

    /**
     * Returns the script {@link #cloneCommand()} runs.
     * <p>
     * Separate and static so a test can run it against a mirror on this machine with real git,
     * rather than asserting that a string contains the words it hopes for. What this script does
     * to a workspace that already holds work is the part worth testing, and no assertion about
     * its text could establish it.
     *
     * @return The shell script.
     */
    static String cloneScript() {
        return "set -e; cd " + MOUNT + "; "
                        + "if [ ! -d .git ]; then git init -q -b main .; "
                        + "git remote add sokar \"$SOKAR_REMOTE_URL\"; fi; "
                        + "git fetch -q sokar 2>/dev/null || true; "
                        + "if ! git rev-parse HEAD >/dev/null 2>&1; then "
                        // Which branch the mirror calls its own, asked rather than assumed: a
                        // repository seeded from a checkout on 'master' has no 'main' at all.
                        + "  git remote set-head sokar -a >/dev/null 2>&1 || true; "
                        + "  start=$(git symbolic-ref -q --short refs/remotes/sokar/HEAD"
                        + " 2>/dev/null || true); "
                        + "  if [ -z \"$start\" ]; then "
                        + "    for candidate in sokar/main sokar/master; do "
                        + "      if git rev-parse -q --verify \"$candidate\" >/dev/null; then "
                        + "        start=$candidate; break; fi; done; fi; "
                        + "  if [ -n \"$start\" ]; then "
                        + "    git checkout -q -B \"${start#sokar/}\" \"$start\"; fi; fi; "
                        + "git config user.name \"${SOKAR_GIT_NAME:-agent}\"; "
                + "git config user.email \"${SOKAR_GIT_EMAIL:-agent@localhost}\"";
    }

    /**
     * Returns the shell that pushes whatever the agent committed, committing what it left
     * uncommitted so that nothing is left behind.
     * <p>
     * Static because it depends on nothing but the mount point and the environment already in the
     * container: a task being rescued has no workspace object left to ask.
     * <p>
     * <strong>Committing comes first, and an empty workspace exits non-zero.</strong> A project
     * with no commit yet has no {@code HEAD}, and the earlier order asked for one before it
     * committed - so a task whose agent had created files in a repository that never had an
     * initial commit printed "nothing to push", exited zero, and was then removed as rescued.
     * Measured: the ref never reached the mirror and the files went with the container. Committing
     * first creates that initial commit, and the only way out without a {@code HEAD} now fails,
     * because a caller that removes a container on success must not be told success for nothing.
     *
     * @return Command and arguments.
     */
    public static java.util.List<String> pushCommand() {
        return java.util.List.of("sh", "-c",
                "set -e; cd " + MOUNT + "; "
                        + "if [ -n \"$(git status --porcelain)\" ]; then "
                        + "  git add -A && git commit -q -m \"agent: uncommitted work\"; fi; "
                        + "if git rev-parse HEAD >/dev/null 2>&1; then "
                        + "  git push -q sokar HEAD:\"$SOKAR_TASK_REF\"; "
                        + "else echo 'nothing to push'; exit 3; fi");
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
     * Because this is assumed rather than discovered, {@link #verify(String, String)} checks the
     * address that was actually used against what the container sees, once the container is up.
     *
     * @return Address of the host as seen from inside a task container.
     */
    public static String gateAddress() {
        return GATE_ADDRESS;
    }

    /**
     * Checks the address the firewall was opened for against what a running container sees.
     * <p>
     * The expected address is passed in rather than read from {@link #gateAddress()}: that is
     * only the fallback for a podman that cannot be asked, and comparing against it reported a
     * gate as firewalled off on every machine where podman answers something else - while the
     * push it warned about worked.
     *
     * @param hosts Content of the container's {@code /etc/hosts}.
     * @param expected Address the ruleset was told to open.
     * @return {@code null} if the mapping is as expected, otherwise a message naming the
     *         difference.
     */
    public static String verify(String hosts, String expected) {
        for (final String line : hosts.split("\n")) {
            final String entry = line.strip();
            if (entry.isEmpty() || entry.startsWith("#")) {
                continue;
            }
            final String[] fields = entry.split("\\s+");
            for (int i = 1; i < fields.length; i++) {
                if (containerVisibleHost().equals(fields[i])) {
                    return expected.equals(fields[0]) ? null
                            : "the container reaches this host at " + fields[0] + ", not "
                                    + expected + "; the git gate is firewalled off";
                }
            }
        }
        return "the container has no " + containerVisibleHost()
                + " entry, so it cannot reach the git gate";
    }
}
