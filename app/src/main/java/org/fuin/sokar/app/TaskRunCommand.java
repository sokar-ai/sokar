package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Runs one agent task against a project, and hands the terminal to a shell inside it.
 */
@Command(name = "run",
        mixinStandardHelpOptions = true,
        description = "Runs a task in a fresh container for the given project.")
public class TaskRunCommand implements Callable<Integer>, SokarFactory.ContextAware {

    /**
     * Hosts whose reachability changes what the git gate is worth, matched on the registrable
     * name so a subdomain counts too.
     */
    private static final java.util.List<String> FORGES = java.util.List.of("github.com",
            "gitlab.com", "bitbucket.org", "codeberg.org", "githubusercontent.com");


    @Parameters(index = "0", arity = "0..1", paramLabel = "<task>",
            description = "Name of the task. Defaults to an interactive shell.")
    private String task = "shell";

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Option(names = "--agent", paramLabel = "<name>",
            description = "Agent to install in the image. Default: the only one installed.")
    private String agentName;

    @Option(names = "--provider", paramLabel = "<name>",
            description = "Provider to serve the models. Default: the agent's own.")
    private String providerName;

    /**
     * The chosen provider, worked out once. Null both before it is resolved and when the agent
     * is not brokered at all, which is why the flag beside it exists.
     */
    @org.jspecify.annotations.Nullable
    private SelectedProvider provider;

    private boolean providerResolved;

    /**
     * Returns the name this task's credential is stored under.
     * <p>
     * <strong>The provider's name, not the agent's.</strong> A credential belongs to whoever
     * issued it: pointing a second agent at a provider the first already uses meant storing the
     * same key twice, under two agent names, with neither saying which provider it was for.
     * <p>
     * A vault written before that change is still read: an entry under the agent's own name is
     * used when there is none under the provider's, so nobody's stored credential stops working
     * on upgrade. {@link #reportLegacyCredential} is what tells them to move it.
     *
     * @param agent The agent.
     * @return Vault key.
     */
    private String credentialName(org.fuin.sokar.agent.api.InstalledAgent agent) {
        final SelectedProvider selection = provider(agent);
        return SelectedProvider.credentialKey(context.credentials().keySet(), agent.name(),
                selection == null ? null : selection.name());
    }

    /**
     * Says once that a credential is stored under the old key.
     *
     * @param agent The agent.
     * @param out Where to report.
     */
    private void reportLegacyCredential(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            InstalledAgent agent, PrintWriter out) {
        if (agent == null) {
            return;
        }
        final SelectedProvider selection = provider(agent);
        if (selection != null && credentialName(agent).equals(agent.name())
                && !agent.name().equals(selection.name())) {
            out.println("credential stored under '" + agent.name() + "', which is this agent's"
                    + " name; it belongs to '" + selection.name() + "'. Move it with:"
                    + " sokar vault put " + selection.name());
        }
    }

    /**
     * Returns the variable this task's credential belongs in.
     * <p>
     * The agent's own answer if it gives one, otherwise the provider's. Most agents no longer
     * give one: which variable an Anthropic key goes in is Anthropic's fact, and an agent that
     * restated it could disagree with the agent beside it.
     *
     * @param agent The agent.
     * @return Variable name, or {@code null} when neither names one.
     */
    @org.jspecify.annotations.Nullable
    private String tokenVariable(org.fuin.sokar.agent.api.InstalledAgent agent) {
        final String type = credentialType(credentialName(agent));
        final SelectedProvider selection = provider(agent);
        return selection == null ? agent.definition().tokenVariable(type)
                : agent.definition().tokenVariable(type, selection.definition());
    }

    /**
     * Returns the provider this task uses, resolving it the first time it is asked for.
     *
     * @param agent The agent, or {@code null}.
     * @return The selection, or {@code null} when nothing is brokered.
     */
    @org.jspecify.annotations.Nullable
    private SelectedProvider provider(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            InstalledAgent agent) {
        if (!providerResolved) {
            providerResolved = true;
            provider = agent == null ? null
                    : SelectedProvider.choose(context.providers(), agent.definition(),
                            providerName);
        }
        return provider;
    }

    @Option(names = "--credential-type", paramLabel = "<type>",
            description = "Overrides the kind recorded with the credential when it was stored.")
    private String credentialType;

    /** Used when neither the flag nor the stored entry says which kind this is. */
    private static final String DEFAULT_CREDENTIAL_TYPE = "api-key";

    /**
     * Says whether the vault's copy has fallen behind the agent's own credential.
     * <p>
     * Only when both are the same kind: an operator who stored a different kind on purpose has
     * not gone stale, and warning them every run would teach them to ignore the line.
     *
     * @param stored What the vault holds, or {@code null}.
     * @param host What the agent holds on this machine, or {@code null}.
     * @return {@code true} when the vault should be refreshed.
     */
    static boolean staleCredential(org.fuin.sokar.vault.@org.jspecify.annotations.Nullable
            VaultEntry stored, org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            Credential host) {
        return stored != null && host != null
                && java.util.Objects.equals(stored.type(), host.type())
                && !stored.value().equals(host.secret());
    }

    /**
     * Reports a vault copy that has fallen behind, without stopping anything.
     *
     * @param agent The selected agent, or {@code null}.
     * @param out Where to report.
     */
    private void reportStaleCredential(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            InstalledAgent agent, PrintWriter out) {
        if (agent == null || agent.definition().configDirectory() == null) {
            return;
        }
        try {
            if (staleCredential(context.credentials().get(credentialName(agent)),
                    agent.extractCredential(VaultImportCommand
                            .expand(agent.definition().configDirectory())).orElse(null))) {
                out.println("credential the vault's copy is older than the one '" + agent.name()
                        + "' holds here; 'sokar vault import " + agent.name() + "' refreshes it");
            }
        } catch (RuntimeException ex) {
            // A freshness check must never be the reason a task does not run.
            return;
        }
    }

    /**
     * Says why this task cannot authenticate, before anything is built.
     *
     * @param agent The selected agent, or {@code null}.
     * @return The reason, or {@code null} when there is nothing in the way.
     */
    @org.jspecify.annotations.Nullable
    private String unbrokerable(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            InstalledAgent agent) {
        final SelectedProvider selection = provider(agent);
        if (agent == null || selection == null) {
            return null;
        }
        final String type = credentialType(credentialName(agent));
        final String reason = selection.route().unbrokerableReason(type);
        return reason == null ? null
                : "the '" + type + "' credential stored for '" + agent.name()
                        + "' cannot be used. " + reason + ".";
    }

    /**
     * Returns which credential kind this task uses.
     * <p>
     * The kind belongs to the secret, so the stored entry decides it and the flag only overrides.
     * Before it was stored the flag was the only source, and forgetting it failed looking exactly
     * like a wrong key.
     *
     * @param agentName Name the credential is stored under.
     * @return The kind.
     */
    private String credentialType(String agentName) {
        if (credentialType != null) {
            return credentialType;
        }
        final var entry = context.credentials().get(agentName);
        return entry == null || entry.type() == null ? DEFAULT_CREDENTIAL_TYPE : entry.type();
    }

    @Option(names = "--token-hours", paramLabel = "<n>",
            description = "How long the phantom token is accepted. Default: ${DEFAULT-VALUE}")
    private int tokenHours = 8;

    @Option(names = "--shell", paramLabel = "<path>",
            description = "Shell to attach. Default: ${DEFAULT-VALUE}")
    private String shell = "/bin/bash";

    @Option(names = "--attach", paramLabel = "<what>",
            description = "What to start on attach: agent or shell. Default: ${DEFAULT-VALUE}")
    private String attach = "agent";

    @Option(names = "--keep",
            description = "Leaves the container in place after the shell exits.")
    private boolean keep;

    @Option(names = "--dry-run",
            description = "Reports what would be done without starting anything.")
    private boolean dryRun;

    @Option(names = { "-P", "--prompt" }, paramLabel = "<text>",
            description = "Runs the agent with this prompt instead of attaching a shell.")
    private String prompt;

    @Option(names = "--model", paramLabel = "<name>",
            description = "Model to ask the agent for. Only if the agent declares a model flag.")
    private String model;

    @Option(names = "--max-turns", paramLabel = "<n>",
            description = "Turn limit. Only if the agent declares one.")
    private Integer maxTurns;

    @Option(names = "--minutes", paramLabel = "<n>",
            description = "How long the agent may run. Default: ${DEFAULT-VALUE}")
    private int minutes = 30;

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream the gate forwards approved pushes to.")
    private String upstream;

    @Option(names = "--no-gate",
            description = "Runs without a workspace or a git gate. The agent gets an empty"
                    + " directory and cannot commit anywhere.")
    private boolean noGate;

    @Option(names = "--clearance", paramLabel = "<mode>",
            description = "What to do with a blocked connection: prompt, allow, deny, or off."
                    + " Default: ${DEFAULT-VALUE}")
    private String clearance = "prompt";

    @Option(names = "--raw",
            description = "Shows the agent's output as it came, without its own formatter.")
    private boolean raw;

    @Option(names = "--no-attach",
            description = "Starts the container and returns, instead of handing over a shell.")
    private boolean noAttach;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    /** The phantom-token environment, kept so the agent run gets the same one the container has. */
    private java.util.Map<String, String> environmentCache = java.util.Map.of();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // Nobody should have to write a file by hand before their first task: every field has
        // a defensible default and the name follows from the directory. Only when someone is
        // there to answer - a script that lands here with no project file is more likely in the
        // wrong directory than wanting one written, and the message below says what to do.
        if (!java.nio.file.Files.exists(projectFile) && System.console() != null) {
            try (java.io.BufferedReader in = new java.io.BufferedReader(
                    new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8))) {
                if (!ProjectWizard.create(projectFile, in, out)) {
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
            project = ProjectReader.read(projectFile);
        } catch (ProjectException ex) {
            // A bad project file is the user's problem to fix, not a defect: report it as one
            // line, not as a stack trace.
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        out.println("task           " + task);
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
            projectOrigins = projectEgress(project, context.paths().egressSets());
        } catch (org.fuin.sokar.shield.EgressSetException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        if (dryRun) {
            // What the project itself opens. The agent and provider are not chosen yet, so this
            // is a preview of the file rather than the full report a real run prints.
            reportReachable(project, projectOrigins, java.util.List.of(), out);
            return 0;
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
                runner.containerName(project, task, String.valueOf(ProcessHandle.current().pid()));

        final TaskWorkspace workspace = openWorkspace(project, out, err);

        try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {

            reportStaleCredential(select(agents), out);
            reportLegacyCredential(select(agents), out);

            final String refusal = unbrokerable(select(agents));
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
                    workspace == null || !workspace.gated() ? null : gateAddress(project),
                    workspace == null ? 0 : workspace.port(), null, null);

            environmentCache = new java.util.LinkedHashMap<>();
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
            final SelectedProvider serving = provider(selected);
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

            final CredentialPlumbing plumbing = startVault(selected, container, out, err);
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
                environmentCache.putAll(workspace.environment(project, task));
                if (!workspace.gated()) {
                    // Pushing to a real upstream needs a credential for it. The key stays in the
                    // vault and the container gets an agent socket, so a task can sign without
                    // ever holding anything it could leak.
                    final java.nio.file.Path sshSocket = startSshAgent(container, out, err);
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
                    final String host = upstreamHost(project.upstream());
                    if (host != null && !domains.contains(host)) {
                        domains.add(host);
                        origins.putIfAbsent(host, "upstream");
                        out.println("upstream  " + host + " (the agent pushes there directly)");
                    }
                }
            }

            reportReachable(project, origins, refused(selected), out);

            // The port is decided before this, so the firewall rule can name it; the gate itself
            // starts afterwards, because its log lives in the state directory that start()
            // creates. Starting it first silently failed to spawn at all.
            runner.start(project, container, layers, environmentCache, domains, wiring, out);

            if (needsRelay && plumbing != null) {
                startRelay(runner, container, plumbing.socket(), out, err);
            }

            if (workspace != null) {
                if (workspace.gated()) {
                    startGate(runner, workspace, container, out, err);
                }
                prepareWorkspace(runner, workspace, container, out, err);
            }

            placeAgentFiles(runner, selected, container, environmentCache, out, err);
            out.println();

            startClearance(runner, project, container, out, err);
            writeResumeRecord(container, err);

            if (prompt != null) {
                return runAgent(runner, agents, container, out, err);
            }

            if (noAttach) {
                // Everything after start() replaces this process, which makes the normal path
                // impossible to drive from a script or a test. This is the seam for both.
                out.println("attached  no");
                out.flush();
                return 0;
            }

            final String startWith = "agent".equals(attach) && selected != null
                    ? selected.definition().binary() : null;
            out.println(keep
                    ? "Attaching. The container is left in place; remove it with"
                            + " 'podman rm -f " + container + "'."
                    : "Attaching. The container is removed when the shell exits.");
            if (startWith != null) {
                out.println("Starting " + startWith + " first; you get a shell when it exits.");
            }
            out.flush();

            // Waits rather than replacing this process, so there is still something here to remove
            // the container when the shell ends.
            return cleanUp(runner, container, context.exec().applyAsInt(
                    runner.attachCommand(container, shell, startWith,
                            project.name() + "/" + task)));

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
     * Opens the git gate for this task, unless the caller asked for none.
     *
     * @return The workspace, or {@code null} when running without a gate.
     */
    private TaskWorkspace openWorkspace(Project project, PrintWriter out, PrintWriter err) {
        if (noGate || dryRun) {
            return null;
        }
        try {
            if (project.securityClass() == org.fuin.sokar.core.project.SecurityClass.ONLINE) {
                // Online takes the gate out of the path entirely: the agent's remote IS the
                // upstream. Nothing is reviewed, which is what the class is for and why a project
                // has to opt into it rather than a task asking for it.
                return TaskWorkspace.direct(project.upstream());
            }
            return TaskWorkspace.gated(
                    GateSupport.gate(project, upstream, seed(project, out)),
                    TaskWorkspace.containerVisibleHost());
        } catch (RuntimeException ex) {
            // A task with no workspace is still a useful task - a shell in a hardened box - so
            // this reports and continues rather than refusing to start.
            err.println("sokar: no git gate for this task: " + ex.getMessage());
            err.flush();
            return null;
        }
    }

    /**
     * Returns the repository an empty mirror should be seeded from.
     * <p>
     * Only reached when neither {@code --upstream} nor the project names one. Standing in a
     * checkout is taken as meaning that checkout, so the common case needs no flag. It is printed
     * rather than assumed silently, and only committed history is copied - a bare clone has no
     * working tree.
     *
     * @param project The project.
     * @param out Where to report.
     * @return Path of the work tree, or {@code null} when there is none.
     */
    @org.jspecify.annotations.Nullable
    private String seed(Project project, PrintWriter out) {
        if (upstream != null || project.upstream() != null
                || java.nio.file.Files.isDirectory(GateSupport.mirror(project).resolve("objects"))) {
            // A mirror that exists is never re-seeded, so saying it would be seeded is a lie.
            return null;
        }
        final java.nio.file.Path local = org.fuin.sokar.gate.LocalRepository.topLevel(
                new org.fuin.sokar.core.process.ProcessCommandRunner(),
                java.nio.file.Path.of("."));
        if (local == null) {
            return null;
        }
        out.println("seed      " + local + " (committed history only)");
        return local.toString();
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
        final String variable = tokenVariable(agent);
        final String token = variable == null ? null : environment.get(variable);
        if (token == null) {
            // Nothing to stand in for, so nothing to place: the agent will ask for a login, which
            // is the honest outcome when no credential was brokered.
            return;
        }
        try {
            final SelectedProvider selection = provider(agent);
            final var files = agent.containerSetup(
                    new org.fuin.sokar.agent.api.SetupContext(token,
                            credentialType(credentialName(agent)), TaskWorkspace.MOUNT,
                            endpointFor(agent, environment),
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
    private void startRelay(TaskRunner runner, String container, java.nio.file.Path socket,
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
                        ProcessHandle.current().info().command().orElse("sokar"),
                        "vault", "relay",
                        "--listen", String.valueOf(TaskWiring.VAULT_PORT),
                        "--socket", socket.toString(),
                        "--pid-file", state.resolve("relay.pid").toString()));
        try {
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("relay.log").toFile())
                    .start();
            recordHelper("relay", command, java.util.Map.of(), TaskHelpers.AFTER);
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
    private String endpointFor(org.fuin.sokar.agent.api.InstalledAgent agent,
            java.util.Map<String, String> environment) {
        final SelectedProvider selection = provider(agent);
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
     * Starts the git gate as its own process.
     * <p>
     * Detached, like the clearance watcher and for the same reason: this process either returns or
     * replaces itself with a shell, and the gate has to be there for as long as the container is.
     * The first version of this held the server in {@code task run} and it died the moment the
     * command finished - the container could then commit and never push.
     * <p>
     * The token goes through the environment, not the command line: a command line is visible in
     * the host's process list, and this token authenticates pushes.
     */
    /**
     * What the credential proxy set up for this task.
     *
     * @param socket Host path of the socket the container mounts.
     * @param upstreamHost Provider host to withhold from the firewall.
     * @param environment Variables the container needs to use the proxy.
     */
    private record CredentialPlumbing(java.nio.file.Path socket, String upstreamHost,
            java.util.Map<String, String> environment) {
    }

    /**
     * Starts the credential proxy for this task, detached, and returns how to reach it.
     * <p>
     * Detached for the same reason as the gate and the clearance watcher: this process either
     * replaces itself with a shell or returns when the agent finishes, and the proxy has to
     * outlive both. It writes a pid file that the poststop hook reaps.
     * <p>
     * <strong>The proxy mints the token, not this process.</strong> The real credential then only
     * ever exists in the proxy, which is the property that makes the phantom token worth having.
     * This process waits for the token file to appear and injects the value into the container.
     *
     * @param agent The selected agent, or {@code null}.
     * @param container Container name.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return The plumbing, or {@code null} when this task brokers no credential.
     */
    /** What this run started on the host, written out so 'task resume' can start it again. */
    private final java.util.List<TaskHelpers.Helper> startedHelpers = new java.util.ArrayList<>();

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

    private CredentialPlumbing startVault(org.fuin.sokar.agent.api.InstalledAgent agent,
            String container, PrintWriter out, PrintWriter err) {

        if (agent == null) {
            return null;
        }
        final SelectedProvider selection = provider(agent);
        final org.fuin.sokar.agent.api.ProviderRoute route =
                selection == null ? null : selection.route();
        final String type = credentialType(credentialName(agent));
        final String variable = tokenVariable(agent);
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
        if (!context.credentials().containsKey(credentialName(agent))) {
            out.println("token     none - the vault holds no credential for '"
                    + credentialName(agent) + "'");
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
                ProcessHandle.current().info().command().orElse("sokar"),
                "vault", "serve",
                "--socket", socket.toString()));
        command.addAll(java.util.List.of(
                "--credential", credentialName(agent),
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
            recordHelper("vault", command, java.util.Map.of(),
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

    private void startGate(TaskRunner runner, TaskWorkspace workspace, String container,
            PrintWriter out, PrintWriter err) {

        final java.nio.file.Path state = context.paths().containerState(container);
        final java.util.List<String> command = java.util.List.of(
                ProcessHandle.current().info().command().orElse("sokar"),
                "gate", "serve",
                "--project", projectFile.toAbsolutePath().toString(),
                // Reachable from the LAN, which is not what anyone would want, but a loopback
                // bind is measurably unreachable from the container here. Narrowing it needs
                // pasta's --map-host-loopback, which cannot be passed this way. Every request
                // carries a per-task token, which is what actually keeps this shut.
                "--address", "0.0.0.0",
                "--port", String.valueOf(workspace.port()),
                "--pid-file", state.resolve("gate.pid").toString());

        try {
            final ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("gate.log").toFile());
            builder.environment().put("SOKAR_GATE_TOKEN", workspace.token().value());
            if (upstream != null) {
                builder.environment().put("SOKAR_GATE_UPSTREAM", upstream);
            }
            builder.start();
            recordHelper("gate", command, java.util.Map.copyOf(builder.environment().entrySet()
                    .stream()
                    .filter(entry -> entry.getKey().startsWith("SOKAR_GATE_"))
                    .collect(java.util.stream.Collectors.toMap(
                            java.util.Map.Entry::getKey, java.util.Map.Entry::getValue))),
                    TaskHelpers.AFTER);
            out.println("gate      " + workspace.url(project(out, err)));
            final String mismatch = gateReachability(runner, container);
            if (mismatch != null) {
                err.println("sokar: " + mismatch);
                err.flush();
            }
            out.flush();
        } catch (java.io.IOException ex) {
            err.println("sokar: could not start the git gate: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Returns the address the firewall must open for the git gate.
     * <p>
     * Asked of podman rather than assumed. The name podman uses does not resolve on the host, so
     * this was a constant - and the constant is wrong on podman 4, which answers with the host's
     * own LAN address instead of pasta's {@code 169.254.1.2}. Ubuntu 24.04 LTS ships podman 4.9.3,
     * so every gate there was firewalled off and every push hung until it timed out.
     * <p>
     * Costs one throwaway container against an image the task already needs. When podman cannot
     * answer, the old constant is used and said so: a wrong guess breaks the gate, which is
     * recoverable, while refusing to run breaks the task, which is not.
     *
     * @param project The project, for the image to ask with.
     * @return Address a container reaches this host at.
     */
    private String gateAddress(org.fuin.sokar.core.project.Project project) {
        final java.util.Optional<String> asked =
                context.podman().hostAddressFromContainer(project.baseImage());
        if (asked.isPresent()) {
            return asked.get();
        }
        spec.commandLine().getErr().println("sokar: could not ask podman where a container reaches"
                + " this host; assuming " + TaskWorkspace.gateAddress()
                + ", and the git gate will not work if that is wrong");
        return TaskWorkspace.gateAddress();
    }

    /**
     * Asks the container where it reaches this host, and compares that with the address the
     * firewall was told to allow.
     * <p>
     * The two are derived independently - one by podman, one by
     * {@link TaskWorkspace#gateAddress()} - and when they disagreed the only symptom was a push
     * that hung for two minutes and then failed to connect. Cheap to check, so it is checked.
     *
     * @param container Container name.
     * @return {@code null} if the gate is reachable, otherwise a message saying why not.
     */
    private String gateReachability(TaskRunner runner, String container) {
        try {
            final java.nio.file.Path out = java.nio.file.Files.createTempFile("sokar-hosts", "");
            try {
                final int code = runner.execute(container, java.util.Map.of(),
                        java.util.List.of("cat", "/etc/hosts"), out,
                        java.time.Duration.ofSeconds(20));
                if (code != 0) {
                    return null;
                }
                return TaskWorkspace.verify(java.nio.file.Files.readString(out));
            } finally {
                java.nio.file.Files.deleteIfExists(out);
            }
        } catch (java.io.IOException ex) {
            return null;
        }
    }

    /**
     * Starts the ssh-agent for this task, detached, and returns the socket to mount.
     * <p>
     * Only for an online project, because only an online project pushes to a remote that wants a
     * key. The key itself stays in the vault: the container gets a socket that signs, so a leak
     * from inside the box yields nothing reusable.
     * <p>
     * Detached and pid-filed for the same reason as the gate and the credential proxy - it has to
     * outlive a {@code task run} that either returns or replaces itself with a shell, and the
     * poststop hook reaps every {@code *.pid} in the state directory.
     *
     * @param container Container name.
     * @param out Where progress is reported.
     * @param err Where problems are reported.
     * @return Host path of the socket, or {@code null} if the agent could not be started.
     */
    private java.nio.file.Path startSshAgent(String container, PrintWriter out, PrintWriter err) {

        final java.nio.file.Path state = context.paths().containerState(container);
        final java.nio.file.Path socket = state.resolve("ssh-agent.sock");
        final java.util.List<String> command = java.util.List.of(
                ProcessHandle.current().info().command().orElse("sokar"),
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

    /**
     * Returns the host part of a git remote, for the firewall and the resolver.
     * <p>
     * Handles the two shapes a git remote actually takes: an SSH one like
     * {@code git@github.com:you/repo.git}, which is not a URL, and an ordinary
     * {@code https://} URL.
     *
     * @param upstream Remote as written in the project file.
     * @return Host, or {@code null} if none can be read.
     */
    static String upstreamHost(String upstream) {
        if (upstream == null || upstream.isBlank()) {
            return null;
        }
        final String value = upstream.strip();
        if (value.contains("://")) {
            final String rest = value.substring(value.indexOf("://") + 3);
            final String authority = rest.split("/", 2)[0];
            final String hostPort = authority.contains("@")
                    ? authority.substring(authority.indexOf('@') + 1) : authority;
            final String host = hostPort.split(":", 2)[0];
            return host.isBlank() ? null : host;
        }
        if (value.contains("@") && value.contains(":")) {
            final String afterUser = value.substring(value.indexOf('@') + 1);
            final String host = afterUser.split(":", 2)[0];
            return host.isBlank() ? null : host;
        }
        return null;
    }

    private Project project(PrintWriter out, PrintWriter err) {
        return ProjectReader.read(projectFile);
    }

    /**
     * Clones the mirror into the container's workspace.
     */
    private void prepareWorkspace(TaskRunner runner, TaskWorkspace workspace, String container,
            PrintWriter out, PrintWriter err) {

        final java.nio.file.Path log =
                context.paths().containerState(container).resolve("workspace.log");
        final int code = runner.execute(container, environmentCache, workspace.cloneCommand(),
                log, java.time.Duration.ofMinutes(5));
        if (code == 0) {
            out.println("workspace " + TaskWorkspace.MOUNT + " ready");
        } else {
            err.println("sokar: could not prepare the workspace, see " + log);
            err.flush();
        }
        out.flush();
    }

    /**
     * Starts the clearance watcher for this container, following the events the reader hook is
     * already writing.
     * <p>
     * Detached on purpose. This process either replaces itself with a shell or returns when the
     * agent finishes, and in both cases the watcher has to outlive it - a blocked connection
     * during an interactive session needs a prompt just as much as one during a headless run. The
     * watcher writes a pid file, and the supervisor hook reaps it at poststop.
     */
    private void startClearance(TaskRunner runner, Project project, String container,
            PrintWriter out, PrintWriter err) {

        if ("off".equals(clearance)) {
            return;
        }

        // The watcher edits the container's live nftables set, which means entering its network
        // namespace, which means knowing its pid. Starting one without it produces a watcher that
        // reaches a verdict and cannot act on it - which reads exactly like a working watcher.
        final java.util.Optional<Long> pid = runner.containerPid(container);
        if (pid.isEmpty()) {
            err.println("sokar: the container reports no process, so no clearance watcher"
                    + " was started; blocked connections will stay blocked");
            err.flush();
            return;
        }

        final java.nio.file.Path state = context.paths().containerState(container);
        final java.nio.file.Path events =
                state.resolve(org.fuin.sokar.wire.ReaderEvents.FILE);

        final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                ProcessHandle.current().info().command().orElse("sokar"),
                "shield", "watch",
                // What the prompt shows. A container name carries a pid and identifies nothing
                // an operator recognises; project and task are what they chose.
                "--project", project.name() + "/" + task,
                "--pid", String.valueOf(pid.get()),
                "--events", events.toString(),
                "--socket", state.resolve("clearance.sock").toString(),
                "--pid-file", state.resolve("watcher.pid").toString()));
        switch (clearance) {
            case "allow" -> command.add("--allow-all");
            case "deny" -> command.add("--deny-all");
            default -> { }
        }

        try {
            new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(state.resolve("clearance.log").toFile())
                    .start();
            // The container pid in here belongs to this run; a resume replaces it with the new one.
            recordHelper("watcher", command, java.util.Map.of(), TaskHelpers.AFTER);
            out.println("clearance " + clearance + ", log at " + state.resolve("clearance.log"));
            out.flush();
        } catch (java.io.IOException ex) {
            // Losing the prompt costs recourse, not containment: the firewall keeps dropping
            // either way. Saying so beats failing a task that may not need it.
            err.println("sokar: could not start the clearance watcher: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Runs the agent, then renders its output with the agent's own formatter.
     * <p>
     * Both halves come from the agent: Sokar knows neither how to invoke it nor how to read what
     * it produced. That is the whole point of the boundary - a second agent with a different
     * command line and a different output format needs no change here.
     */
    private int runAgent(TaskRunner runner, org.fuin.sokar.agent.api.InstalledAgents agents,
            String container, PrintWriter out, PrintWriter err) {

        final org.fuin.sokar.agent.api.InstalledAgent agent = select(agents);
        if (agent == null) {
            err.println("sokar: --prompt needs an agent, and none is installed");
            err.flush();
            return 69;
        }

        final java.nio.file.Path log = context.paths().containerState(container).resolve("task.log");
        final org.fuin.sokar.agent.api.RunRequest request = new org.fuin.sokar.agent.api.RunRequest(
                prompt, model, maxTurns, null, false, !raw);

        out.println();
        out.println("running   " + agent.name() + " (up to " + minutes + " minutes)");
        out.flush();

        int code;
        String timedOut = null;
        try {
            code = runner.runAgent(agent, container, request,
                    environmentCache, log, java.time.Duration.ofMinutes(minutes));
        } catch (org.fuin.sokar.runtime.ContainerException ex) {
            // Whatever the agent managed to say before it was killed is the most useful thing
            // there is at this point. Throwing here would discard it, which is the opposite of
            // what someone diagnosing a stuck run needs.
            code = 124;
            timedOut = ex.getMessage();
        }

        out.println();
        try {
            if (raw) {
                java.nio.file.Files.readAllLines(log).forEach(out::println);
            } else {
                // The agent renders its own output. A second agent with a different format needs
                // no change here, which is the property the reference implementation lost by
                // branching on the agent's name in its log viewer.
                final int[] shown = { 0 };
                agent.formatLog(log, line -> {
                    shown[0]++;
                    out.println(line);
                });
                if (shown[0] == 0 && java.nio.file.Files.size(log) > 0) {
                    // The formatter suppressed everything - which is right for a run that only
                    // produced setup chatter, and unhelpful when that is all there was. Silence
                    // is the one thing that tells a reader nothing, so fall back to the raw text.
                    out.println("(nothing to render; showing the raw output)");
                    out.println();
                    java.nio.file.Files.readAllLines(log).forEach(out::println);
                }
            }
        } catch (java.io.IOException | RuntimeException ex) {
            err.println("sokar: cannot render the agent output at " + log + ": " + ex.getMessage());
            err.flush();
        }
        out.flush();

        out.println();
        if (timedOut != null) {
            err.println("sokar: " + timedOut);
            err.flush();
        }
        out.println("agent exited with " + code + "; output kept at " + log);
        out.flush();
        return code == 0 ? 0 : 70;
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

        final String variable = tokenVariable(agent);
        if (variable == null) {
            return java.util.Map.of();
        }

        final var credentials = context.credentials();
        if (!credentials.containsKey(credentialName(agent))) {
            // Reported once already, where the proxy would have been started.
            return java.util.Map.of();
        }

        final java.util.Map<String, String> secrets = new java.util.LinkedHashMap<>();
        credentials.forEach((key, entry) -> secrets.put(key, entry.value()));
        final org.fuin.sokar.vault.TokenBroker broker =
                new org.fuin.sokar.vault.TokenBroker(() -> secrets);
        final org.fuin.sokar.vault.PhantomToken token =
                broker.mint(credentialName(agent), task, java.time.Duration.ofHours(tokenHours));

        out.println("token     " + variable + "="
                + org.fuin.sokar.vault.PhantomToken.abbreviate(token.value()));
        return java.util.Map.of(variable, token.value());
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
            layers = layers.and(stage(agent, project, out), java.util.List.of());
            layers = layers.and(java.util.List.of(), agent.definition().installAsAgent());
        }

        if (!project.imageSnippetLines().isEmpty()) {
            out.println("snippet   " + project.imageSnippetLines().size() + " lines from the project");
            layers = layers.and(project.imageSnippetLines(), java.util.List.of());
        }
        return layers;
    }

    /**
     * Copies what the agent ships into the build context and returns the lines that install it.
     * <p>
     * A tool that is a tree of files rather than one binary has no URL to pin, so its own package
     * carries it and the image build fetches nothing at all. That is why the copy happens here:
     * the container runtime can only see what is inside the build context.
     *
     * @param agent The agent.
     * @param project The project being built.
     * @param out Where progress is reported.
     * @return Build lines, empty when the agent ships nothing.
     */
    private java.util.List<String> stage(org.fuin.sokar.agent.api.InstalledAgent agent,
            Project project, PrintWriter out) {

        final java.util.List<String> lines = new java.util.ArrayList<>();
        for (final var tree : agent.definition().packaged()) {
            final java.nio.file.Path source = java.nio.file.Path.of(tree.source());
            if (!java.nio.file.Files.exists(source)) {
                out.println("missing   " + source + ", which " + agent.name() + " says it ships");
                continue;
            }
            final java.nio.file.Path staged = context.paths().buildContext(project.name())
                    .resolve(tree.stagingName());
            try {
                if (upToDate(source, staged)) {
                    // Staged by an earlier run of the same package. Unpacking hundreds of
                    // megabytes again on every task would be the slowest thing a task run does.
                    out.println("packaged  " + tree.target() + ", already staged");
                } else if (tree.archive()) {
                    unpack(source, staged);
                    markStaged(source, staged);
                } else {
                    copyTree(source, staged);
                    markStaged(source, staged);
                }
            } catch (java.io.IOException | RuntimeException ex) {
                out.println("missing   could not stage " + source + ": " + ex.getMessage());
                continue;
            }
            lines.add("COPY " + tree.stagingName() + " " + tree.target());
            out.println("packaged  " + tree.target() + " from this agent's own package");
        }
        return java.util.List.copyOf(lines);
    }

    /** Records which source a staged directory came from, so it is not unpacked twice. */
    private static java.nio.file.Path marker(java.nio.file.Path staged) {
        return staged.resolveSibling(staged.getFileName() + ".from");
    }

    private static String stamp(java.nio.file.Path source) throws java.io.IOException {
        return source + " " + java.nio.file.Files.size(source) + " "
                + java.nio.file.Files.getLastModifiedTime(source).toMillis();
    }

    private static boolean upToDate(java.nio.file.Path source, java.nio.file.Path staged) {
        try {
            return java.nio.file.Files.isDirectory(staged)
                    && java.nio.file.Files.exists(marker(staged))
                    && java.nio.file.Files.readString(marker(staged)).equals(stamp(source));
        } catch (java.io.IOException ex) {
            return false;
        }
    }

    private static void markStaged(java.nio.file.Path source, java.nio.file.Path staged)
            throws java.io.IOException {
        java.nio.file.Files.writeString(marker(staged), stamp(source));
    }

    /**
     * Unpacks an archive into the build context.
     *
     * @param source Archive to unpack.
     * @param target Directory to unpack into, replacing whatever was there.
     * @throws IOException If it cannot be unpacked.
     */
    private void unpack(java.nio.file.Path source, java.nio.file.Path target)
            throws java.io.IOException {

        deleteTree(target);
        java.nio.file.Files.createDirectories(target);
        final var result = context.runner().run(org.fuin.sokar.core.process.Command.of(
                java.util.List.of("tar", "-xzf", source.toString(), "-C", target.toString())));
        if (!result.successful()) {
            throw new java.io.IOException("tar failed: " + result.standardError().strip());
        }
    }

    private static void deleteTree(java.nio.file.Path directory) throws java.io.IOException {
        if (!java.nio.file.Files.exists(directory)) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(directory)) {
            for (final java.nio.file.Path path
                    : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    /**
     * Copies a directory, replacing whatever was there.
     *
     * @param source Directory to copy.
     * @param target Where to put it.
     * @throws IOException If it cannot be copied.
     */
    private static void copyTree(java.nio.file.Path source, java.nio.file.Path target)
            throws java.io.IOException {

        deleteTree(target);
        try (var walk = java.nio.file.Files.walk(source)) {
            for (final java.nio.file.Path path : walk.toList()) {
                final java.nio.file.Path destination = target.resolve(source.relativize(path));
                if (java.nio.file.Files.isDirectory(path)) {
                    java.nio.file.Files.createDirectories(destination);
                } else {
                    java.nio.file.Files.createDirectories(destination.getParent());
                    java.nio.file.Files.copy(path, destination,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private org.fuin.sokar.agent.api.InstalledAgent select(
            org.fuin.sokar.agent.api.InstalledAgents agents) {
        if (agentName != null) {
            return agents.require(agentName);
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

    private int cleanUp(TaskRunner runner, String container, int code) {
        if (!keep) {
            // The container may or may not exist: podman rm tolerates both, and leaving a created
            // container behind is worse than an extra command.
            runner.remove(container);
        }
        // Only reaps when no container is running: a start that failed fires no poststop hook.
        runner.reapOrphans(container);
        return code;
    }
    /**
     * Returns the hosts the project declared, each mapped to where it came from.
     *
     * @param project The project.
     * @param sets Where the installed sets are found.
     * @return Host to origin, empty when the project declared nothing.
     */
    static java.util.Map<String, String> projectEgress(Project project,
            org.fuin.sokar.shield.EgressSetDirectory sets) {
        final org.fuin.sokar.core.project.Egress egress = project.egress();
        if (egress.isEmpty()) {
            return java.util.Map.of();
        }
        final java.util.Map<String, String> origins = new java.util.LinkedHashMap<>();
        sets.origins(egress.sets()).forEach((host, set) -> origins.put(host, "set " + set));
        // A directly named host wins the label: an operator who wrote it down should see it
        // reported as their own decision, not as whichever set happens to contain it too.
        egress.domains().forEach(domain -> origins.put(domain, "project"));
        return origins;
    }

    /**
     * Returns the destinations an agent asks for and is deliberately not given.
     *
     * @param selected The chosen agent, or {@code null}.
     * @return Hosts, empty when the agent names none.
     */
    static java.util.List<String> refused(
            org.fuin.sokar.agent.api.InstalledAgent selected) {
        return selected == null ? java.util.List.of() : selected.definition().refusedDomains();
    }

    /**
     * Prints every destination a task may reach, with who decided it.
     * <p>
     * One list rather than a line per source, because the question an operator has is "what can
     * this reach, and who said so" - and four differently shaped lines do not answer it. A
     * destination that was deliberately refused is listed too, and marked: "we said no" and
     * "nobody mentioned it" are different states, and only one of them is a thing to go and fix.
     *
     * @param project The project.
     * @param origins Host to the origin that granted it, in the order the sources were consulted.
     * @param refused Hosts an agent declares it asks for and is not given.
     * @param out Where to report.
     */
    static void reportReachable(Project project, java.util.Map<String, String> origins,
            java.util.List<String> refused, PrintWriter out) {

        if (origins.isEmpty() && refused.isEmpty()) {
            out.println("reachable      nothing - no agent, provider or project declared a host");
            out.flush();
            return;
        }

        final int width = java.util.stream.Stream.concat(origins.keySet().stream(),
                        refused.stream())
                .mapToInt(String::length).max().orElse(0);

        String label = "reachable";
        for (final java.util.Map.Entry<String, String> entry : origins.entrySet()) {
            out.printf("%-14s %-" + width + "s  %s%n", label, entry.getKey(), entry.getValue());
            label = "";
        }
        for (final String host : refused) {
            out.printf("%-14s %-" + width + "s  %s%n", label, host, "refused on purpose");
            label = "";
        }
        out.println("               ports 80 and 443; everything else is NXDOMAIN");

        // Said once, here, where the grants are. Not refused: an agent legitimately clones
        // dependencies from a forge.
        final java.util.List<String> forges = origins.keySet().stream()
                .filter(host -> FORGES.stream().anyMatch(forge ->
                        host.equals(forge) || host.endsWith("." + forge)))
                .toList();
        if (!forges.isEmpty()
                && project.securityClass() == org.fuin.sokar.core.project.SecurityClass.GUARDED) {
            out.println("               " + String.join(", ", forges) + " is reachable, so the"
                    + " gate now rests on this container holding no credential for it");
        }
        out.flush();
    }
}
