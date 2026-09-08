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
     * Exit code recorded for a run that was signalled rather than ended.
     * <p>
     * 128 plus SIGINT, the shell's own convention. The value matters less than that it is not
     * zero: {@code cleanUp} stops and keeps a task that ended badly, and an interrupted run is
     * one - the workspace may hold commits that never reached the gate.
     */
    static final int INTERRUPTED = 130;

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

    /**
     * Returns how somebody is meant to be involved in this task.
     * <p>
     * Derived here, once, from what was asked for: a prompt means nobody is expected to be
     * watching, and otherwise it is whichever session was asked to be attached. Written down at
     * start rather than re-derived later, because a task that has finished no longer has flags to
     * derive it from.
     *
     * @return The mode.
     */
    private org.fuin.sokar.wire.TaskMode mode() {
        if (prompt != null) {
            return org.fuin.sokar.wire.TaskMode.UNATTENDED;
        }
        return "shell".equals(attach) ? org.fuin.sokar.wire.TaskMode.SHELL
                : org.fuin.sokar.wire.TaskMode.AGENT;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        // Starting the task is the domain's job; what this class adds is the terminal. The
        // daemon builds the same request and gets the same behavior without running a CLI.
        final TaskLaunch launch = new TaskLaunch(context, new TaskLaunch.Request(task, projectFile,
                agentName, providerName, credentialType, tokenHours, upstream, noGate, dryRun,
                clearance, keep, mode(), prompt, model, maxTurns, minutes));

        return launch.launch(out, err, running -> {

            environmentCache = running.environment();

            if (prompt != null) {
                // The run itself belongs to the launch, which the daemon calls too; what is left
                // here is showing it to a person.
                final int code = launch.runAgent(running.runner(), running.selected(),
                        running.container(), running.environment(), out, err);
                if (code == TaskLaunch.NO_AGENT) {
                    // A refusal, not a failed run: there is no log to render and nothing to say
                    // beyond what was already said.
                    return code;
                }
                render(running.selected(), running.container(), out, err);
                return code == 0 ? 0 : 70;
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
                    : "Attaching. Leaving the shell removes the container; Ctrl-C stops it and"
                            + " keeps it, because an interrupted run may hold work.");
            if (startWith != null) {
                out.println("Starting " + startWith + " first; you get a shell when it exits.");
            }
            out.flush();

            // Waits rather than replacing this process, so there is still something here to
            // remove the container when the shell ends.
            //
            // Through a Teardown rather than a plain finally, because the way this ends most
            // often is not a return: Ctrl-C and a closed terminal signal the whole process group,
            // which kills the gate, the broker and the watcher and leaves the container running
            // without them. The status starts at "interrupted" so that a signal takes the
            // keep-and-stop branch - work that never reached the gate is still in there.
            final java.util.concurrent.atomic.AtomicInteger status =
                    new java.util.concurrent.atomic.AtomicInteger(INTERRUPTED);
            try (Teardown teardown =
                    Teardown.arm(() -> running.cleanUp().applyAsInt(status.get()))) {
                status.set(context.exec().applyAsInt(
                        running.runner().attachCommand(running.container(), shell, startWith,
                                running.project().name() + "/" + task)));
            }
            return status.get();
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
    /**
     * Shows what the agent produced, which is the half of an unattended run that needs a person.
     * <p>
     * The agent renders its own log. A second agent with a different format needs no change here,
     * which is the property the reference implementation lost by branching on the agent's name in
     * its log viewer.
     *
     * @param agent The agent that ran, or {@code null} when none did.
     * @param container Container name.
     * @param out Where the output goes.
     * @param err Where a rendering failure is reported.
     */
    private void render(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable InstalledAgent
            agent, String container, PrintWriter out, PrintWriter err) {

        final java.nio.file.Path log =
                context.paths().containerState(container).resolve("task.log");
        out.println();
        try {
            if (raw || agent == null) {
                java.nio.file.Files.readAllLines(log).forEach(out::println);
            } else {
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
        out.println();
        out.println("output kept at " + log);
        out.flush();
    }


}
