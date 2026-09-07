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

    @Option(names = "--provider", paramLabel = "<name>",
            description = "Provider to serve the models. Default: the agent's own.")
    private String providerName;

    @Option(names = "--credential-type", paramLabel = "<type>",
            description = "Overrides the kind recorded with the credential when it was stored.")
    private String credentialType;

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

        // Starting the task is the domain's job; what this class adds is the terminal. The
        // daemon builds the same request and gets the same behaviour without running a CLI.
        final TaskLaunch launch = new TaskLaunch(context, new TaskLaunch.Request(task, projectFile,
                agentName, providerName, credentialType, tokenHours, upstream, noGate, dryRun,
                clearance, keep));

        return launch.launch(out, err, running -> {

            environmentCache = running.environment();

            if (prompt != null) {
                return runAgent(running.runner(), running.selected(), running.container(), out, err);
            }

            if (noAttach) {
                // Everything below replaces this process, which makes the normal path impossible
                // to drive from a script or a test. This is the seam for both.
                out.println("attached  no");
                out.flush();
                return 0;
            }

            final String startWith = "agent".equals(attach) && running.selected() != null
                    ? running.selected().definition().binary() : null;
            out.println(keep
                    ? "Attaching. The container is left in place; remove it with"
                            + " 'podman rm -f " + running.container() + "'."
                    : "Attaching. The container is removed when the shell exits.");
            if (startWith != null) {
                out.println("Starting " + startWith + " first; you get a shell when it exits.");
            }
            out.flush();

            // Waits rather than replacing this process, so there is still something here to
            // remove the container when the shell ends.
            return running.cleanUp().applyAsInt(context.exec().applyAsInt(
                    running.runner().attachCommand(running.container(), shell, startWith,
                            running.project().name() + "/" + task)));
        });
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

    /**
     * Runs the agent, then renders its output with the agent's own formatter.
     * <p>
     * Both halves come from the agent: Sokar knows neither how to invoke it nor how to read what
     * it produced. That is the whole point of the boundary - a second agent with a different
     * command line and a different output format needs no change here.
     */
    private int runAgent(TaskRunner runner,
            org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable InstalledAgent agent,
            String container, PrintWriter out, PrintWriter err) {

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

}
