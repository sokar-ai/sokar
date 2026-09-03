package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.runtime.ContainerName;
import org.fuin.sokar.runtime.ContainerSpec;
import org.fuin.sokar.runtime.Podman;
import org.fuin.sokar.shield.NftRuleset;
import org.fuin.sokar.wire.Sidecar;

/**
 * Runs one task: generate the policy, build the image, create the container, start it.
 * <p>
 * The order matters and is not an implementation detail. The ruleset and the sidecar are written
 * <em>before</em> the container is created, because the nft hook reads them while the container is
 * being created. Writing them afterwards would leave a window in which a container exists without
 * its firewall, and the hook would fail closed rather than wait.
 */
public class TaskRunner {

    private final Podman podman;

    private final SokarPaths paths;

    /**
     * Constructor.
     *
     * @param runner Runs external programs.
     * @param paths Where files go.
     */
    public TaskRunner(CommandRunner runner, SokarPaths paths) {
        this.podman = new Podman(runner);
        this.paths = paths;
    }

    /**
     * Returns the container name for one task run.
     *
     * @param project The project.
     * @param task Task name.
     * @param runId Identifier unique within the task.
     * @return Container name.
     */
    public String containerName(Project project, String task, String runId) {
        return ContainerName.of(project, task, runId);
    }

    /**
     * Prepares and starts a container for one task.
     * <p>
     * The name is passed in rather than returned, so that a caller can clean up a container that
     * was created but failed to start. Returning it would leave that container behind, because the
     * failure happens before the return.
     *
     * @param project The project.
     * @param container Container name.
     * @param out Where progress is reported.
     * @throws IOException If a file cannot be written.
     */
    public void start(Project project, String container, PrintWriter out) throws IOException {
        start(project, container, org.fuin.sokar.runtime.ImageLayers.none(), out);
    }

    /**
     * Prepares and starts a container for one task, with layers contributed by an agent and by
     * the project itself.
     *
     * @param project The project.
     * @param container Container name.
     * @param layers What the agent and the project add to the image.
     * @param out Where progress is reported.
     * @throws IOException If a file cannot be written.
     */
    public void start(Project project, String container,
            org.fuin.sokar.runtime.ImageLayers layers, PrintWriter out) throws IOException {
        start(project, container, layers, java.util.Map.of(), out);
    }

    /**
     * Prepares and starts a container for one task.
     *
     * @param project The project.
     * @param container Container name.
     * @param layers What the agent and the project add to the image.
     * @param environment Variables to set inside the container. Phantom tokens only.
     * @param out Where progress is reported.
     * @throws IOException If a file cannot be written.
     */
    public void start(Project project, String container,
            org.fuin.sokar.runtime.ImageLayers layers,
            java.util.Map<String, String> environment, PrintWriter out) throws IOException {
        start(project, container, layers, environment, java.util.List.of(), out);
    }

    /**
     * Prepares and starts a container for one task.
     *
     * @param project The project.
     * @param container Container name.
     * @param layers What the agent and the project add to the image.
     * @param environment Variables to set inside the container. Phantom tokens only.
     * @param allowedDomains Domains the container's resolver will answer for.
     * @param out Where progress is reported.
     * @throws IOException If a file cannot be written.
     */
    public void start(Project project, String container,
            org.fuin.sokar.runtime.ImageLayers layers,
            java.util.Map<String, String> environment,
            java.util.List<String> allowedDomains, PrintWriter out) throws IOException {
        start(project, container, layers, environment, allowedDomains, TaskWiring.none(), out);
    }

    /**
     * Prepares and starts a container for one task.
     *
     * @param project The project.
     * @param container Container name.
     * @param layers What the agent and the project add to the image.
     * @param environment Variables to set inside the container.
     * @param allowedDomains Domains the container's resolver will answer for.
     * @param wiring Host-side endpoints this container is attached to.
     * @param out Where progress is reported.
     * @throws IOException If a file cannot be written.
     */
    public void start(Project project, String container,
            org.fuin.sokar.runtime.ImageLayers layers,
            java.util.Map<String, String> environment,
            java.util.List<String> allowedDomains,
            TaskWiring wiring, PrintWriter out) throws IOException {

        final Path state = paths.containerState(container);
        Files.createDirectories(state);

        final Path ruleset = state.resolve("ruleset.nft");
        Files.writeString(ruleset,
                rulesetFor(project, hostResolvers(), wiring.gateAddress(), wiring.gatePort()),
                StandardCharsets.UTF_8);
        out.println("policy    " + ruleset);

        // Written before the container is created, like the ruleset: the supervisor hook reads
        // it while the container is coming up.
        final Path dnsConfig = state.resolve("dns.conf");
        Files.writeString(dnsConfig, dnsPolicyFor(project, allowedDomains).render(),
                StandardCharsets.UTF_8);
        out.println("resolver  " + dnsConfig
                + (allowedDomains.isEmpty() ? " (no domains allowed)"
                        : " (" + allowedDomains.size() + " domains)"));

        final Path sidecarFile = state.resolve("sidecar.json");
        new Sidecar(Sidecar.VERSION, project.name(),
                project.securityClass().name().toLowerCase(),
                ruleset.toString(), dnsConfig.toString(), sokarBinary(), state.toString())
                .writeTo(sidecarFile);
        out.println("sidecar   " + sidecarFile);

        final String image = podman.buildImage(project, paths.buildContext(project.name()), layers);
        out.println("image     " + image);

        final ContainerSpec specification = new ContainerSpec(container, image)
                .command("sleep", "infinity")
                .resolver(org.fuin.sokar.shield.DnsPolicy.LISTEN_ADDRESS)
                .annotation(Sidecar.ANNOTATION, sidecarFile.toString());
        environment.forEach(specification::environment);
        if (wiring.vaultSocket() != null) {
            // The credential proxy. Mounted rather than reached over the network on purpose: it
            // needs no firewall rule, and the provider's own host is withheld from the ruleset
            // so this is the only route to a working credential.
            specification.volume(wiring.vaultSocket(), TaskWiring.VAULT_MOUNT);
        }
        if (wiring.sshSocket() != null) {
            // The ssh-agent. The private key never crosses this: only signatures do.
            specification.volume(wiring.sshSocket(), TaskWiring.SSH_MOUNT);
        }
        podman.create(specification);
        out.println("container " + container);

        // If the nft hook fails, this is where it stops: the container never reaches running.
        podman.start(container);
        out.println("started   yes");
    }

    /**
     * Returns the path of the running sokar binary, for the hooks to start helpers with.
     *
     * @return Absolute path, or the bare name if this process cannot see its own path - which
     *         happens under a JVM and is why the hooks check the file before using it.
     */
    private String sokarBinary() {
        return ProcessHandle.current().info().command().orElse("sokar");
    }

    private org.fuin.sokar.shield.DnsPolicy dnsPolicyFor(Project project,
            java.util.List<String> allowedDomains) {
        final org.fuin.sokar.shield.DnsPolicy policy =
                new org.fuin.sokar.shield.DnsPolicy(project.securityClass());
        // Every allowed domain, not just the upstream. Resolving a name and being allowed to
        // reach it are the same decision: a domain that resolves but is then dropped produces a
        // clearance prompt for a host the definition already declared, which is a prompt about
        // nothing. The prompt is for what an agent reached for that nobody declared.
        allowedDomains.forEach(policy::autoAllow);
        // The upstream resolvers the host itself uses. Anything the policy does not allow is
        // NXDOMAIN before it ever reaches them.
        hostResolvers().forEach(policy::upstream);
        return policy;
    }

    /**
     * Returns the resolvers the host uses, so allowed queries go somewhere real.
     *
     * @return Resolver addresses, falling back to a public one when /etc/resolv.conf says nothing
     *         usable. The fallback matters: the container's own resolv.conf points at loopback, so
     *         inheriting it would make the resolver forward to itself.
     */
    private java.util.List<String> hostResolvers() {
        final java.util.List<String> found = new java.util.ArrayList<>();
        try {
            for (final String line : Files.readAllLines(Path.of("/etc/resolv.conf"))) {
                if (line.startsWith("nameserver ")) {
                    final String address = line.substring("nameserver ".length()).strip();
                    if (!address.startsWith("127.") && !address.contains(":")) {
                        found.add(address);
                    }
                }
            }
        } catch (IOException ex) {
            // Fall through to the default below.
        }
        return found.isEmpty() ? java.util.List.of("8.8.8.8") : found;
    }

    /**
     * Resolves the upstream's addresses, so the firewall can name them.
     * <p>
     * Pinned at task start rather than followed: a large host rotates addresses, and a task that
     * runs long enough for that to matter will see a clearance prompt for the new one, which is
     * the safe way to be wrong.
     *
     * @param upstream Remote as written in the project file.
     * @return Addresses, empty when the host cannot be resolved.
     */
    private static java.util.List<String> upstreamAddresses(String upstream) {
        final String host = TaskRunCommand.upstreamHost(upstream);
        if (host == null) {
            return java.util.List.of();
        }
        try {
            return java.util.Arrays.stream(java.net.InetAddress.getAllByName(host))
                    .filter(address -> address instanceof java.net.Inet4Address)
                    .map(java.net.InetAddress::getHostAddress)
                    .distinct()
                    .toList();
        } catch (java.net.UnknownHostException ex) {
            // Reported by the resolver line instead; a task that cannot resolve its upstream is
            // a task whose push will fail loudly rather than silently.
            return java.util.List.of();
        }
    }

    private String rulesetFor(Project project, java.util.List<String> upstreamResolvers,
            String gateAddress, int gatePort) {
        final NftRuleset ruleset = new NftRuleset(project.securityClass());
        if (gateAddress != null) {
            // Before the security-class check on purpose: the gate is on this machine, and an
            // offline project still has to be able to commit.
            ruleset.gate(gateAddress, gatePort);
        }
        if (project.securityClass() != SecurityClass.OFFLINE) {
            ruleset.allowV4("127.0.0.0/8");
            // The resolver runs INSIDE this namespace, so its own upstream queries are subject to
            // this ruleset. Without these rules dnsmasq answers every query with REFUSED and the
            // container looks like it has no network at all - which is what happened the first
            // time this was wired up.
            upstreamResolvers.forEach(ruleset::resolver);
        }
        return ruleset.render();
    }

    /**
     * Runs an agent inside a running container.
     * <p>
     * The command is built by the agent itself, over varlink, from its own declared flags -
     * Sokar does not know how to invoke it and should not learn. The output is written to a file
     * so the same agent can be asked to render it afterwards.
     *
     * @param agent The installed agent.
     * @param container Container to run in.
     * @param request What to ask the agent to do.
     * @param environment Variables for the run; phantom tokens only.
     * @param logFile Where the agent's raw output goes.
     * @param timeout How long the agent may run.
     * @return Exit code of the agent.
     */
    public int runAgent(org.fuin.sokar.agent.api.InstalledAgent agent, String container,
            org.fuin.sokar.agent.api.RunRequest request,
            java.util.Map<String, String> environment,
            Path logFile, java.time.Duration timeout) {
        return podman.execute(container, environment, agent.buildCommand(request), logFile, timeout);
    }

    /**
     * Runs a command inside a running container.
     *
     * @param container Container name.
     * @param environment Variables for this command.
     * @param command Program and arguments.
     * @param log File the output goes to.
     * @param timeout How long it may run.
     * @return Exit code.
     */
    public int execute(String container, java.util.Map<String, String> environment,
            java.util.List<String> command, Path log, java.time.Duration timeout) {
        return podman.execute(container, environment, command, log, timeout);
    }

    /**
     * Returns the host process id of a running container's init process.
     *
     * @param container Container name.
     * @return Process id, or empty if it is not running.
     */
    public java.util.Optional<Long> containerPid(String container) {
        return podman.pidOf(container);
    }

    /**
     * Returns the command that attaches a shell to a running container.
     *
     * @param container Container name.
     * @param shell Shell to run.
     * @return Argument list.
     */
    public List<String> attachCommand(String container, String shell) {
        return podman.attachArguments(container, shell);
    }

    /**
     * Stops and removes a container.
     *
     * @param container Container name.
     */
    public void remove(String container) {
        podman.remove(container);
    }
}
