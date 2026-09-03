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

    @Parameters(index = "0", arity = "0..1", paramLabel = "<task>",
            description = "Name of the task. Defaults to an interactive shell.")
    private String task = "shell";

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Option(names = "--agent", paramLabel = "<name>",
            description = "Agent to install in the image. Default: the only one installed.")
    private String agentName;

    @Option(names = "--credential-type", paramLabel = "<type>",
            description = "Which credential shape the agent gets. Default: ${DEFAULT-VALUE}")
    private String credentialType = "api-key";

    @Option(names = "--token-hours", paramLabel = "<n>",
            description = "How long the phantom token is accepted. Default: ${DEFAULT-VALUE}")
    private int tokenHours = 8;

    @Option(names = "--shell", paramLabel = "<path>",
            description = "Shell to attach. Default: ${DEFAULT-VALUE}")
    private String shell = "/bin/bash";

    @Option(names = "--keep",
            description = "Leaves the container in place after the shell exits.")
    private boolean keep;

    @Option(names = "--dry-run",
            description = "Reports what would be done without starting anything.")
    private boolean dryRun;

    @Option(names = "--no-attach",
            description = "Starts the container and returns, instead of handing over a shell.")
    private boolean noAttach;

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

        if (dryRun) {
            return 0;
        }

        if (!context.hooks().binariesPresent()) {
            // Without the hooks the container comes up with no firewall at all. Saying so is the
            // only safe outcome: starting it anyway is the failure Sokar exists to prevent.
            err.println("sokar: the hook binaries are not installed, run 'sokar setup' first");
            err.flush();
            return 69;
        }

        final TaskRunner runner = context.tasks();
        final String container =
                runner.containerName(project, task, String.valueOf(ProcessHandle.current().pid()));

        try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {

            final org.fuin.sokar.runtime.ImageLayers layers;
            try {
                layers = layers(agents, project, out);
            } catch (org.fuin.sokar.agent.api.AgentException ex) {
                err.println("sokar: " + ex.getMessage());
                err.flush();
                return 69;
            }

            final org.fuin.sokar.agent.api.InstalledAgent selected = select(agents);
            final java.util.List<String> domains = selected == null
                    ? java.util.List.of() : selected.definition().allowedDomains();

            runner.start(project, container, layers, environment(agents, out, err), domains, out);
            out.println();

            if (noAttach) {
                // Everything after start() replaces this process, which makes the normal path
                // impossible to drive from a script or a test. This is the seam for both.
                out.println("attached  no");
                out.flush();
                return 0;
            }

            if (!keep) {
                out.println("Attaching. The container is removed when the shell exits.");
            }
            out.flush();

            // Nothing after this line runs in the shipped binary: the process image is replaced by
            // the shell.
            context.exec().accept(runner.attachCommand(container, shell));
            return 0;

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

        final String variable = agent.definition().tokenVariable(credentialType);
        if (variable == null) {
            return java.util.Map.of();
        }

        final java.util.Map<String, String> credentials = context.credentials();
        if (!credentials.containsKey(agent.name())) {
            out.println("token     none - the vault holds no credential for '" + agent.name() + "'");
            return java.util.Map.of();
        }

        final org.fuin.sokar.vault.TokenBroker broker =
                new org.fuin.sokar.vault.TokenBroker(() -> credentials);
        final org.fuin.sokar.vault.PhantomToken token =
                broker.mint(agent.name(), task, java.time.Duration.ofHours(tokenHours));

        out.println("token     " + variable + "=" + token);
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

    private int cleanUp(TaskRunner runner, String container, int code) {
        if (!keep) {
            // The container may or may not exist: podman rm tolerates both, and leaving a created
            // container behind is worse than an extra command.
            runner.remove(container);
        }
        return code;
    }
}
