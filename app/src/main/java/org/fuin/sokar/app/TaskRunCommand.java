package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Starts one agent task against a project, and hands the terminal to a shell inside it.
 * <p>
 * <strong>One verb for two cases.</strong> A task that does not exist is created; one that exists
 * and is stopped is brought back with the workspace, the branch and the uncommitted changes it
 * has. The caller cannot know which case they are in - that is what {@code startAction} on the
 * listing is for - and pressing should not be how they find out. A task that is already running is
 * refused rather than started twice.
 * <p>
 * <strong>The container is kept unless {@code --rm} says otherwise.</strong> This used to be the
 * other way round: the command a person reaches for first removed what it had just made, which is
 * how an operator lost a task by typing the obvious thing.
 */
@Command(name = "start",
        mixinStandardHelpOptions = true,
        description = "Starts a task: creates it, or brings back the one that is stopped.")
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

    @Option(names = { "-p", "--project" }, paramLabel = "<name>", required = true,
            description = "Project name, as 'sokar project list' prints it.")
    private String projectName;

    @Option(names = "--agent", paramLabel = "<name>",
            description = "Agent to install in the image. Default: the only one installed.")
    private @Nullable String agentName;

    @Option(names = "--provider", paramLabel = "<name>",
            description = "Provider to serve the models. Default: the agent's own.")
    private @Nullable String providerName;

    @Option(names = "--credential", paramLabel = "<entry>=<destination>",
            description = "Another credential this task holds, beyond the agent's own and the project's: a vault"
                    + " entry and the destination or provider it is for. Repeatable. It reaches the task as"
                    + " SOKAR_TOKEN_<ENTRY> and SOKAR_URL_<ENTRY>, a token worthless anywhere else.")
    private java.util.Map<String, String> credentials = new java.util.LinkedHashMap<>();

    @Option(names = "--credential-type", paramLabel = "<type>",
            description = "Overrides the kind recorded with the credential when it was stored.")
    private @Nullable String credentialType;

    @Option(names = "--token-hours", paramLabel = "<n>",
            description = "How long the phantom token is accepted. Default: ${DEFAULT-VALUE}")
    private int tokenHours = 8;

    @Option(names = "--shell", paramLabel = "<path>",
            description = "Shell to attach. Default: ${DEFAULT-VALUE}")
    private String shell = "/bin/bash";

    @Option(names = "--attach", paramLabel = "<what>",
            description = "What to start on attach: agent or shell. Default: ${DEFAULT-VALUE}")
    private String attach = "agent";

    @Option(names = "--rm",
            description = "Removes the container when the session ends. Default: it is kept.")
    private boolean rm;

    @Option(names = "--dry-run",
            description = "Reports what would be done without starting anything.")
    private boolean dryRun;

    @Option(names = { "-P", "--prompt" }, paramLabel = "<text>",
            description = "Runs the agent with this prompt instead of attaching a shell.")
    private @Nullable String prompt;

    @Option(names = "--model", paramLabel = "<name>",
            description = "Model to ask the agent for. Only if the agent declares a model flag.")
    private @Nullable String model;

    @Option(names = "--max-turns", paramLabel = "<n>",
            description = "Turn limit. Only if the agent declares one.")
    private @Nullable Integer maxTurns;

    @Option(names = "--minutes", paramLabel = "<n>",
            description = "How long the agent may run. Default: ${DEFAULT-VALUE}")
    private int minutes = 30;

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream the gate forwards approved pushes to.")
    private @Nullable String upstream;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories the task works on. Required:"
                    + " there is no default, and the project's own repository is one of the"
                    + " choices.")
    private @Nullable String repository;

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

    @Option(names = "--detach",
            description = "Starts the task and returns, instead of handing over a shell.")
    private boolean detach;

    @Option(names = "--now",
            description = "Returns as soon as the task exists, without waiting for the image"
                    + " build. Needs --detach: there is nothing to attach to yet.")
    private boolean now;

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

        if (now && !detach) {
            // Refused rather than guessed at. Returning before the build finishes and then
            // attaching would mean attaching to an image that is not there yet.
            err.println("sokar: --now needs --detach: there is nothing to attach to until the"
                    + " image is built.");
            err.flush();
            return 64;
        }

        final Path projectFile;
        try {
            // A name, not a path: which project a task belongs to must not depend on which
            // directory somebody was standing in. Where the file comes from is ProjectSource's
            // answer, and it prefers the one this machine verified.
            projectFile = ProjectSource.require(context, projectName);
        } catch (final ProjectException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        // Starting the task is the domain's job; what this class adds is the terminal. The
        // daemon builds the same request and gets the same behavior without running a CLI.
        final TaskLaunch launch = new TaskLaunch(context, new TaskLaunch.Request(task, projectFile,
                agentName, providerName, credentialType, tokenHours, upstream, noGate, dryRun,
                clearance, !rm, mode(), prompt, model, maxTurns, minutes, repository, credentials));

        final TaskLaunch.Existing existing = launch.startExisting(out, err);
        if (existing != null) {
            if (existing.code() != 0) {
                return existing.code();
            }
            if (prompt != null) {
                // Brought back with a prompt: run it, continuing the conversation where the agent can.
                final int code = launch.runAgentInExisting(existing.container(),
                        (agent, container) -> render(agent, container, out, err), out, err);
                return code == 0 || code == TaskLaunch.NO_AGENT ? code : 70;
            }
            if (detach) {
                return existing.code();
            }
            // An agent task whose session is gone starts its agent again, continuing where it can.
            return context.exec().applyAsInt(context.tasks().attachCommand(existing.container(),
                    shell, new TaskSession(context).attachedAgent(existing.container(), out),
                    existing.project() + "/" + org.fuin.sokar.runtime.ContainerName
                            .taskIn(existing.project(), existing.container())));
        }

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

            if (detach) {
                // Everything below replaces this process, which makes the normal path impossible
                // to drive from a script or a test. This is the seam for both.
                out.println("attached  no");
                out.flush();
                return 0;
            }

            // The same arguments the unattended path passes. An attached run is still a run
            // inside the box, so an agent that would stop to ask permission is turned off here
            // too - reported from the test machine, where an attached claude asked to approve a
            // command the container had already made safe.
            final String startWith = "agent".equals(attach) && running.selected() != null
                    ? org.fuin.sokar.runtime.ShellWords.quote(
                            running.selected().definition().attendedCommand(model))
                    : null;
            out.println(rm
                    ? "Attaching. Leaving the shell removes the container; Ctrl-C stops it and"
                            + " keeps it, because an interrupted run may hold work."
                    : "Attaching. The container is kept when you leave; remove it with"
                            + " 'sokar task remove " + running.container() + "'.");
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
            final java.util.concurrent.atomic.AtomicInteger outcome =
                    new java.util.concurrent.atomic.AtomicInteger(INTERRUPTED);
            int left;
            // Leaving the window is not finishing the work, and both end the command this
            // terminal is attached to. The session's own shell is what tells them apart: it
            // writes a marker when it returns, and the absence of one keeps the task.
            //
            // Asking tmux whether the session survived was tried first. It does NOT race - that
            // was assumed and then measured on 2026-09-12, five probes, "gone" every time - but it
            // fails in the wrong direction: any container that cannot be asked answers "no
            // session", which reads as "finished", which removes a task.
            //
            // Absent a marker the task is KEPT. Tearing down under a running agent would stop the
            // gate, the broker and the clearance watcher, which is precisely the state the
            // contract warns about: a container that is up with no helpers.
            try (Teardown teardown = Teardown.arm(() -> {
                if (!running.runner().sessionEnded(running.container())) {
                    out.println();
                    out.println("detached  " + running.container() + " is still running");
                    out.println("          come back with 'sokar task attach "
                            + running.container() + "'");
                    out.println("          stop it with 'sokar task stop " + running.container()
                            + "'");
                    out.flush();
                    return;
                }
                running.cleanUp().applyAsInt(outcome.get());
            })) {
                left = context.exec().applyAsInt(
                        running.runner().attachCommand(running.container(), shell, startWith,
                                running.project().name() + "/" + task));
                // A shell exits with its last command's status, which is not a verdict on the
                // task. Reported from a machine where a typo at the prompt - 'bash: /exit: No
                // such file or directory' - made leaving the session print "it failed, so
                // nothing was removed" and keep the container. Somebody who walks out of a
                // shell has ended the task normally, whatever they last typed; only a signal
                // means they did not get to finish, and that is what the starting value is for.
                outcome.set(0);
            }
            return left;
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
    static @Nullable String upstreamHost(@Nullable String upstream) {
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
