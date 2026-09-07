package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.function.IntUnaryOperator;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.agent.api.InstalledAgents;
import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import org.jspecify.annotations.Nullable;

/**
 * Starting a task: from reading the project file to a container that is up, wired and firewalled.
 * <p>
 * Lifted out of {@code TaskRunCommand} after the six wirings, so that starting a task is something
 * the domain does rather than something a CLI class does. [0001] asks the CLI and the interface to
 * reach identical behaviour through the same calls, and this was the last operation where they did
 * not: the daemon had to spawn the CLI and read a line of its output, because running the command
 * was the only way to start a task.
 * <p>
 * <strong>The caller is handed the running task inside this class's own resource scope.</strong>
 * The installed agents are processes this opens and closes, and the paths that follow a start -
 * running the agent to completion, attaching a terminal - use them. Returning before closing them
 * would leak a process per task; closing them first would break the attach.
 * <p>
 * Public, unlike the wirings beside it, because the daemon calls it: that is the whole point of
 * lifting it out of the command.
 */
public final class TaskLaunch {

    /**
     * What to start, as the operator asked for it.
     *
     * @param task Task name, which becomes the ref an agent pushes to.
     * @param projectFile The project file to read.
     * @param agentName Value of {@code --agent}, or {@code null} for the only one installed.
     * @param providerName Value of {@code --provider}, or {@code null}.
     * @param credentialType Value of {@code --credential-type}, or {@code null}.
     * @param tokenHours How long the task's own token lasts.
     * @param upstream Value of {@code --upstream}, or {@code null}.
     * @param noGate Whether to run without a workspace and gate.
     * @param dryRun Whether to stop after reporting what the project opens.
     * @param clearance What to do about a blocked connection: prompt, allow, deny or off.
     * @param keep Whether the container survives the end of the run.
     */
    public record Request(String task, Path projectFile, @Nullable String agentName,
            @Nullable String providerName, @Nullable String credentialType, int tokenHours,
            @Nullable String upstream, boolean noGate, boolean dryRun, String clearance,
            boolean keep) {
    }

    /**
     * A task that is up, and what a caller needs to do anything with it.
     *
     * @param runner Runs commands in it.
     * @param agents The installed agents, open only for the duration of the call.
     * @param selected The agent this task runs, or {@code null}.
     * @param container Container name.
     * @param environment What the container was given.
     * @param project The project it belongs to.
     * @param cleanUp Turns an exit code into the final one, removing the container unless the
     *        run asked to keep it. Handed over rather than duplicated: whether a container
     *        survives is one decision, and the attaching path needs it too.
     */
    public record Running(TaskRunner runner, InstalledAgents agents, @Nullable InstalledAgent selected,
            String container, java.util.Map<String, String> environment, Project project,
            IntUnaryOperator cleanUp) {
    }

    /** What happens once a task is up. */
    @FunctionalInterface
    public interface AfterStart {

        /**
         * Takes over a running task.
         *
         * @param running The task, valid only for the duration of this call.
         * @return The exit code the run ends with.
         * @throws Exception If taking over fails; the launch reports it and cleans up.
         */
        int started(Running running) throws Exception;
    }

    private final SokarContext context;

    private final Request request;

    /**
     * Constructor.
     *
     * @param context Where podman, the paths and the vault come from.
     * @param request What to start.
     */
    public TaskLaunch(SokarContext context, Request request) {
        this.context = context;
        this.request = request;
    }

    /**
     * Starts a task and hands the running thing back to the caller.
     *
     * @param out Where progress is reported.
     * @param err Where refusals and failures are reported.
     * @param after What to do once it is up.
     * @return The exit code.
     */
    public int launch(PrintWriter out, PrintWriter err, AfterStart after) {

        // Nobody should have to write a file by hand before their first task: every field has
        // a defensible default and the name follows from the directory. Only when someone is
        // there to answer - a script that lands here with no project file is more likely in the
        // wrong directory than wanting one written, and the message below says what to do.
        if (!java.nio.file.Files.exists(request.projectFile()) && System.console() != null) {
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                if (!ProjectWizard.create(request.projectFile(), in, out)) {
                    out.flush();
                    return 2;
                }
                out.println();
                out.flush();
            } catch (java.io.IOException ex) {
                err.println("sokar: " + ex.getMessage());
                err.flush();
                return 2;
            }
        }

        final Project project;
        try {
            project = ProjectReader.read(request.projectFile());
        } catch (ProjectException ex) {
            // A bad project file is the user's problem to fix, not a defect: report it as one
            // line, not as a stack trace.
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        out.println("task           " + request.task());
        out.println("project        " + project.name());
        out.println("security class " + project.securityClass().name().toLowerCase());
        out.println("base image     " + project.baseImage());
        out.println("task image     " + project.imageName());
        out.flush();

        // Resolved here rather than at the point of use: a set name that does not exist is a
        // mistake in the project file, and the place to report one of those is beside the other
        // project-file errors - before an image is built, and where --dry-run can still see it.
        final java.util.Map<String, String> projectOrigins;
        try {
            projectOrigins = EgressReport.projectEgress(project, context.paths().egressSets());
        } catch (org.fuin.sokar.shield.EgressSetException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        if (request.dryRun()) {
            // What the project itself opens. The agent and provider are not chosen yet, so this
            // is a preview of the file rather than the full report a real run prints.
            EgressReport.reportReachable(project, projectOrigins, java.util.List.of(), out);
            return 0;
        }

        final java.util.Optional<String> tooOld = context.podman().unsupportedVersion();
        if (tooOld.isPresent()) {
            // Refused rather than run with less than the guarantees Sokar describes. podman 4 has
            // no pasta, so the git gate cannot bind loopback and the endpoint a task pushes to
            // sits on the operator's network. Ubuntu 24.04 LTS ships 4.9.3 and never will ship
            // anything newer - podman is in universe, and a stable release does not change major
            // versions - so this is a real refusal, and the way out is a newer distribution.
            err.println("sokar: " + tooOld.get());
            err.flush();
            return 69;
        }

        final org.fuin.sokar.runtime.HookInstaller.Registration hooks =
                context.hooks().registration();
        if (hooks != org.fuin.sokar.runtime.HookInstaller.Registration.ACTIVE) {
            // Without the hooks the container comes up with no firewall at all. Saying so is the
            // only safe outcome: starting it anyway is the failure Sokar exists to prevent. The
            // binaries being present is not enough - podman has to be told to run them, and a
            // later drop-in can point it somewhere else.
            err.println(switch (hooks) {
                case MISSING -> "sokar: the hooks are not registered with podman,"
                        + " run 'sokar setup' first";
                case DANGLING -> "sokar: the hook descriptors name binaries that are not"
                        + " installed, run 'sokar setup' again";
                case SHADOWED -> "sokar: another containers.conf.d drop-in points hooks_dir at "
                        + context.hooks().effectiveHooksDirectories()
                        + ", so Sokar's hooks would not run";
                case ACTIVE -> "";
            });
            err.flush();
            return 69;
        }

        final TaskRunner runner = context.tasks();
        final String container =
                runner.containerName(project, request.task(), String.valueOf(ProcessHandle.current().pid()));

        final TaskWorkspace workspace = workspace().openWorkspace(project, !request.noGate() && !request.dryRun(), out, err);

        try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {

            credentials().reportStaleCredential(select(agents), out);
            credentials().reportLegacyCredential(select(agents), out);

            final String refusal = credentials().unbrokerable(select(agents));
            if (refusal != null) {
                // Before the image, the container and the token: a task that cannot authenticate
                // fails inside the box with a message about the operator's network.
                err.println("sokar: " + refusal);
                err.println("sokar: import or store a usable one, then run again;"
                        + " --credential-type overrides the stored kind for one run");
                err.flush();
                return 69;
            }

            final org.fuin.sokar.runtime.ImageLayers layers;
            try {
                layers = layers(agents, project, out);
            } catch (org.fuin.sokar.agent.api.AgentException ex) {
                err.println("sokar: " + ex.getMessage());
                err.flush();
                return 69;
            }

            final org.fuin.sokar.agent.api.InstalledAgent selected = select(agents);

            // The state directory has to exist before anything writes into it. start() also
            // creates it, but the credential proxy needs it first: it writes its socket, its
            // token and its pid there, and it has to be listening before the container exists.
            final java.nio.file.Path state = context.paths().containerState(container);
            java.nio.file.Files.createDirectories(state);

            TaskWiring wiring = new TaskWiring(
                    workspace == null || !workspace.gated() ? null : gate().gateAddress(project, err),
                    workspace == null ? 0 : workspace.port(), null, null);

            java.util.Map<String, String> environmentCache = new java.util.LinkedHashMap<>();
            final java.util.List<String> domains = new java.util.ArrayList<>(selected == null
                    ? java.util.List.of() : selected.definition().allowedDomains());
            // Every destination carries where it came from, so the report at the end of this can
            // answer "who decided this" rather than only "what is open".
            final java.util.Map<String, String> origins = new java.util.LinkedHashMap<>();
            if (selected != null) {
                selected.definition().allowedDomains().forEach(host ->
                        origins.putIfAbsent(host, "agent " + selected.definition().name()));
            }

            // An agent that can only address a URL still gets the broker on its socket: only the
            // listening end moves into the container's namespace, and that is a relay started
            // after the container exists.
            final SelectedProvider serving = credentials().provider(selected);
            // The provider's own host, which the agent no longer restates. It has to be here or
            // the firewall denies it: measured, Claude Code contacts the provider before it
            // starts and quits when it cannot, whatever the credential is.
            if (serving != null) {
                for (final String host : serving.definition().domains()) {
                    if (!domains.contains(host)) {
                        domains.add(host);
                    }
                    origins.putIfAbsent(host, "provider " + serving.definition().name());
                }
            }
            projectOrigins.forEach((host, origin) -> {
                if (!domains.contains(host)) {
                    domains.add(host);
                }
                origins.putIfAbsent(host, origin);
            });

            final boolean needsRelay = serving != null
                    && serving.route().endpoint()
                            == org.fuin.sokar.agent.api.ProviderRoute.Endpoint.URL;

            final CredentialWiring.CredentialPlumbing plumbing = wiring().startVault(selected, container, out, err);
            if (plumbing != null) {
                environmentCache.putAll(plumbing.environment());
                wiring = wiring.withVaultSocket(plumbing.socket());
                // Left reachable. Withholding it stopped every agent that checks the provider is
                // up before it starts, and what it kept in was the phantom token: random,
                // task-scoped, and worth nothing to the provider. The real credential is what
                // must not get out, and it never enters the container at all.
                out.println("provider  " + plumbing.upstreamHost()
                        + " reachable; the credential is not, only a task-scoped token");
            } else {
                environmentCache.putAll(environment(agents, out, err));
            }

            if (workspace != null) {
                environmentCache.putAll(workspace.environment(project, request.task()));
                if (!workspace.gated()) {
                    // Pushing to a real upstream needs a credential for it. The key stays in the
                    // vault and the container gets an agent socket, so a task can sign without
                    // ever holding anything it could leak.
                    final java.nio.file.Path sshSocket = wiring().startSshAgent(container, out, err);
                    if (sshSocket != null) {
                        wiring = wiring.withSshSocket(sshSocket);
                        environmentCache.put("SSH_AUTH_SOCK", TaskWiring.SSH_MOUNT);
                        // Host keys cannot be known in advance for an arbitrary upstream, and a
                        // prompt in a container nobody is watching hangs the push. Trust on first
                        // use, recorded, and only reachable through the egress rules above.
                        environmentCache.put("GIT_SSH_COMMAND",
                                "ssh -o StrictHostKeyChecking=accept-new"
                                + " -o UserKnownHostsFile=/home/agent/.ssh/known_hosts");
                    }
                    // The upstream is on the internet, so an online task needs it resolvable and
                    // reachable. A gated task never does: its remote is on this machine.
                    final String host = TaskRunCommand.upstreamHost(project.upstream());
                    if (host != null && !domains.contains(host)) {
                        domains.add(host);
                        origins.putIfAbsent(host, "upstream");
                        out.println("upstream  " + host + " (the agent pushes there directly)");
                    }
                }
            }

            EgressReport.reportReachable(project, origins, EgressReport.refused(selected), out);

            // The port is decided before this, so the firewall rule can name it; the gate itself
            // starts afterwards, because its log lives in the state directory that start()
            // creates. Starting it first silently failed to spawn at all.
            runner.start(project, container, layers, environmentCache, domains, wiring, out);

            if (needsRelay && plumbing != null) {
                wiring().startRelay(runner, container, plumbing.socket(), out, err);
            }

            if (workspace != null) {
                if (workspace.gated()) {
                    gate().startGate(runner, workspace, wiring.gateAddress(),
                            container, project, out, err);
                }
                workspace().prepareWorkspace(runner, workspace, container, environmentCache, out, err);
            }

            placeAgentFiles(runner, selected, container, environmentCache, out, err);
            out.println();

            clearance().startClearance(runner, project, container, out, err);
            writeResumeRecord(container, err);

            // The task is up. What happens next - run the agent to completion, attach a
            // terminal, or simply say so - is the caller's, and it happens inside this scope
            // on purpose: the installed agents are a resource this owns, and the paths that
            // follow a start use them.
            return after.started(new Running(runner, agents, selected, container,
                    environmentCache, project, code -> cleanUp(runner, container, code)));

        } catch (CommandException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return cleanUp(runner, container, 70);
        } catch (Exception ex) {
            err.println("sokar: " + ex);
            err.flush();
            return cleanUp(runner, container, 70);
        }
    }

    /**
     * Records a helper, so a resumed task can start the same thing rather than guess at it.
     *
     * @param name Helper name, matching its pid file.
     * @param command How it was started.
     * @param environment Extra variables it was given.
     * @param phase Whether it has to be up before the container, or needs the running container.
     */
    private void recordHelper(String name, java.util.List<String> command,
            java.util.Map<String, String> environment, String phase) {
        startedHelpers.add(new TaskHelpers.Helper(name, java.util.List.copyOf(command),
                java.util.Map.copyOf(environment), phase));
    }

    private Project project(PrintWriter out, PrintWriter err) {
        return ProjectReader.read(request.projectFile());
    }

    /**
     * Builds the image layers for this run: the agent's, then the project's own.
     * <p>
     * The project's go last so they can rely on the agent already being installed - which is the
     * usual reason for having them at all.
     */
    private org.fuin.sokar.runtime.ImageLayers layers(
            org.fuin.sokar.agent.api.InstalledAgents agents, Project project, PrintWriter out) {

        org.fuin.sokar.runtime.ImageLayers layers = org.fuin.sokar.runtime.ImageLayers.none();

        final org.fuin.sokar.agent.api.InstalledAgent agent = select(agents);
        if (agent != null) {
            out.println("agent     " + agent.name() + " "
                    + (agent.definition().version() == null ? "" : agent.definition().version()));
            layers = layers.and(agent.definition().installAsRoot(),
                    org.fuin.sokar.agent.api.InstallScript.render(agent.definition().artifacts()));
            layers = layers.and(
                    AgentStaging.stage(agent, project, context.paths(),
                            context.runner(), out),
                    java.util.List.of());
            layers = layers.and(java.util.List.of(), agent.definition().installAsAgent());
        }

        if (!project.imageSnippetLines().isEmpty()) {
            out.println("snippet   " + project.imageSnippetLines().size() + " lines from the project");
            layers = layers.and(project.imageSnippetLines(), java.util.List.of());
        }
        return layers;
    }

    private org.fuin.sokar.agent.api.InstalledAgent select(
            org.fuin.sokar.agent.api.InstalledAgents agents) {
        if (request.agentName() != null) {
            return agents.require(request.agentName());
        }
        if (agents.size() == 1) {
            return agents.all().getFirst();
        }
        if (agents.size() == 0) {
            // A task with no agent is legitimate - the walking skeleton attaches a shell - so this
            // is not an error, only an image without an agent in it.
            return null;
        }
        throw new org.fuin.sokar.agent.api.AgentException(
                "Several agents are installed (" + String.join(", ", agents.names())
                        + "), so --agent is required");
    }

    /**
     * Builds the environment the container starts with.
     * <p>
     * <strong>A phantom token, never the real credential.</strong> The agent inside the container
     * gets something that only Sokar's broker accepts, only for this task, and only until it ends.
     * An agent that leaks it has leaked something that stops working when the container does.
     * <p>
     * Empty when the vault holds nothing for this agent - which is not an error. A task that only
     * needs a shell needs no credential, and failing here would make the common case depend on the
     * uncommon one.
     */
    private java.util.Map<String, String> environment(
            org.fuin.sokar.agent.api.InstalledAgents agents, PrintWriter out, PrintWriter err) {

        final org.fuin.sokar.agent.api.InstalledAgent agent = select(agents);
        if (agent == null) {
            return java.util.Map.of();
        }

        final String variable = credentials().tokenVariable(agent);
        if (variable == null) {
            return java.util.Map.of();
        }

        final var credentials = context.credentials();
        if (!credentials.containsKey(credentials().credentialName(agent))) {
            // Reported once already, where the proxy would have been started.
            return java.util.Map.of();
        }

        final java.util.Map<String, String> secrets = new java.util.LinkedHashMap<>();
        credentials.forEach((key, entry) -> secrets.put(key, entry.value()));
        final org.fuin.sokar.vault.TokenBroker broker =
                new org.fuin.sokar.vault.TokenBroker(() -> secrets);
        final org.fuin.sokar.vault.PhantomToken token =
                broker.mint(credentials().credentialName(agent), request.task(), java.time.Duration.ofHours(request.tokenHours()));

        out.println("token     " + variable + "="
                + org.fuin.sokar.vault.PhantomToken.abbreviate(token.value()));
        return java.util.Map.of(variable, token.value());
    }

    /**
     * Writes whatever the agent needs before it will run in a container it has never seen.
     * <p>
     * A vendor's tool expects to have been used once already - a wizard answered, a folder
     * trusted, a credential where its own login would have put it. None of that exists in a fresh
     * container, so the tool stops and asks a person. The agent says what to write; this writes
     * it, understanding none of it.
     *
     * @param runner Runs the container.
     * @param agent The selected agent, or {@code null}.
     * @param container Container name.
     * @param environment Variables already prepared for the task.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     */
    private void placeAgentFiles(TaskRunner runner,
            org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable InstalledAgent agent,
            String container, java.util.Map<String, String> environment, PrintWriter out,
            PrintWriter err) {

        if (agent == null) {
            return;
        }
        final String variable = credentials().tokenVariable(agent);
        final String token = variable == null ? null : environment.get(variable);
        if (token == null) {
            // Nothing to stand in for, so nothing to place: the agent will ask for a login, which
            // is the honest outcome when no credential was brokered.
            return;
        }
        try {
            final SelectedProvider selection = credentials().provider(agent);
            final var files = agent.containerSetup(
                    new org.fuin.sokar.agent.api.SetupContext(token,
                            credentials().credentialType(credentials().credentialName(agent)), TaskWorkspace.MOUNT,
                            wiring().endpointFor(agent, environment),
                            selection == null ? "" : selection.name()));
            for (final var file : files) {
                runner.place(container, file);
            }
            if (!files.isEmpty()) {
                out.println("prepared  " + files.size() + " file(s) the agent needs to start"
                        + " without being asked");
            }
        } catch (java.io.IOException | RuntimeException ex) {
            // Not fatal: the agent still runs, it just asks the questions this would have
            // answered. Saying so beats a task that looks wired up and then stops for input.
            err.println("sokar: could not prepare the agent's files: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Writes down what this run started, so the task can be resumed after it is stopped.
     *
     * @param container Container name.
     * @param err Where a failure is reported.
     */
    private void writeResumeRecord(String container, PrintWriter err) {
        if (startedHelpers.isEmpty()) {
            return;
        }
        try {
            new TaskHelpers(java.util.List.copyOf(startedHelpers))
                    .writeTo(context.paths().containerState(container));
        } catch (java.io.IOException ex) {
            // The task runs regardless; only resuming it later is lost.
            err.println("sokar: could not record how to resume this task: " + ex.getMessage());
            err.flush();
        }
    }

    private CredentialChoice credentials() {
        if (credentials == null) {
            credentials = new CredentialChoice(context, request.providerName(), request.credentialType(), request.agentName());
        }
        return credentials;
    }

    private CredentialWiring wiring() {
        if (wiring == null) {
            wiring = new CredentialWiring(context, credentials(), this::recordHelper, request.task(),
                    request.tokenHours(), request.upstream());
        }
        return wiring;
    }

    private GateWiring gate() {
        if (gate == null) {
            gate = new GateWiring(context, this::recordHelper, request.projectFile(), request.upstream());
        }
        return gate;
    }

    private WorkspaceSetup workspace() {
        if (workspace == null) {
            workspace = new WorkspaceSetup(context, request.task(), request.upstream());
        }
        return workspace;
    }

    private ClearanceWiring clearance() {
        if (clearanceWiring == null) {
            clearanceWiring = new ClearanceWiring(context, this::recordHelper, request.task(), request.clearance());
        }
        return clearanceWiring;
    }

    /** What this run started on the host, written out so 'task resume' can start it again. */
    private final java.util.List<TaskHelpers.Helper> startedHelpers = new java.util.ArrayList<>();

    /**
     * Which credential this task uses, and what to say about it. Built on first use because it
     * reads the options, and those are not set until picocli has parsed them.
     */
    private CredentialChoice credentials;

    /** The broker, the relay and the signing agent this task needs. Built on first use. */
    private CredentialWiring wiring;

    /** How the git gate is bound and firewalled for this task. Built on first use. */
    private GateWiring gate;

    /** The repository the agent works in. Built on first use. */
    private WorkspaceSetup workspace;

    /** The watcher that asks about blocked connections. Built on first use. */
    private ClearanceWiring clearanceWiring;

    private int cleanUp(TaskRunner runner, String container, int code) {
        if (!request.keep()) {
            // The container may or may not exist: podman rm tolerates both, and leaving a created
            // container behind is worse than an extra command.
            runner.remove(container);
        }
        // Only reaps when no container is running: a start that failed fires no poststop hook.
        runner.reapOrphans(container);
        return code;
    }
}
