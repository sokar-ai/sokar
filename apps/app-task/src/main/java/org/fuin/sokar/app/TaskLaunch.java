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
 * the domain does rather than something a CLI class does. The CLI and the interface have to reach
 * identical behavior through the same calls, and this was the last operation where they did not:
 * the daemon had to spawn the CLI and read a line of its output, because running the command was
 * the only way to start a task.
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

    /** The variable a task is told its repository's issue prefixes in, comma-separated. */
    public static final String ISSUE_PREFIXES = "SOKAR_ISSUE_PREFIXES";


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
     * @param repository Which of the project's repositories the task works on. Never guessed: a
     *        request that names none is refused, because a command whose meaning depends on how
     *        many repositories exist today is one nobody can read.
     */
    public record Request(String task, Path projectFile, @Nullable String agentName,
            @Nullable String providerName, @Nullable String credentialType, int tokenHours,
            @Nullable String upstream, boolean noGate, boolean dryRun, String clearance,
            boolean keep, org.fuin.sokar.wire.TaskMode mode, @Nullable String prompt,
            @Nullable String model, @Nullable Integer maxTurns, int minutes,
            @Nullable String repository, java.util.Map<String, String> credentials) {

        /**
         * Constructor for a request that names no credentials of its own beyond the project's.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null}.
         * @param providerName Provider, or {@code null}.
         * @param credentialType Credential type override, or {@code null}.
         * @param tokenHours Phantom token lifetime.
         * @param upstream Upstream override, or {@code null}.
         * @param noGate Whether to skip the gate.
         * @param dryRun Whether to stop before starting.
         * @param clearance Clearance mode.
         * @param keep Whether to keep the container.
         * @param mode What the task is for.
         * @param prompt Prompt, or {@code null}.
         * @param model Model, or {@code null}.
         * @param maxTurns Turn limit, or {@code null}.
         * @param minutes Minutes an unattended run may take.
         * @param repository Which repository, or {@code null}.
         */
        public Request(String task, Path projectFile, @Nullable String agentName, @Nullable String providerName,
                @Nullable String credentialType, int tokenHours, @Nullable String upstream, boolean noGate,
                boolean dryRun, String clearance, boolean keep, org.fuin.sokar.wire.TaskMode mode,
                @Nullable String prompt, @Nullable String model, @Nullable Integer maxTurns, int minutes,
                @Nullable String repository) {
            this(task, projectFile, agentName, providerName, credentialType, tokenHours, upstream, noGate, dryRun,
                    clearance, keep, mode, prompt, model, maxTurns, minutes, repository, java.util.Map.of());
        }

        /**
         * Constructor for a request that predates the repository being named.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null}.
         * @param providerName Provider to use, or {@code null}.
         * @param credentialType Credential kind, or {@code null}.
         * @param tokenHours How long the phantom token is accepted.
         * @param upstream Upstream for the gate, or {@code null}.
         * @param noGate Whether to run without a workspace and gate.
         * @param dryRun Whether to report rather than start.
         * @param clearance What to do with a blocked connection.
         * @param keep Whether the container survives the run.
         * @param mode How somebody is involved.
         * @param prompt What to run unattended, or {@code null}.
         * @param model Model to ask for, or {@code null}.
         * @param maxTurns Turn limit, or {@code null}.
         * @param minutes How long the agent may run.
         */
        public Request(String task, Path projectFile, @Nullable String agentName,
                @Nullable String providerName, @Nullable String credentialType, int tokenHours,
                @Nullable String upstream, boolean noGate, boolean dryRun, String clearance,
                boolean keep, org.fuin.sokar.wire.TaskMode mode, @Nullable String prompt,
                @Nullable String model, @Nullable Integer maxTurns, int minutes) {
            this(task, projectFile, agentName, providerName, credentialType, tokenHours, upstream,
                    noGate, dryRun, clearance, keep, mode, prompt, model, maxTurns, minutes, null);
        }

        /**
         * Constructor for a request that does not say how somebody is involved.
         *
         * @param task Task name.
         * @param projectFile Project file.
         * @param agentName Agent to use, or {@code null} for the only one installed.
         * @param providerName Provider to use, or {@code null} for the agent's own.
         * @param credentialType Credential kind, or {@code null} for the stored one.
         * @param tokenHours How long the phantom token is accepted.
         * @param upstream Upstream for the gate, or {@code null}.
         * @param noGate Whether to run without a workspace and gate.
         * @param dryRun Whether to report rather than start.
         * @param clearance What to do with a blocked connection.
         * @param keep Whether the container survives the run.
         */
        public Request(String task, Path projectFile, @Nullable String agentName,
                @Nullable String providerName, @Nullable String credentialType, int tokenHours,
                @Nullable String upstream, boolean noGate, boolean dryRun, String clearance,
                boolean keep) {
            this(task, projectFile, agentName, providerName, credentialType, tokenHours, upstream,
                    noGate, dryRun, clearance, keep, org.fuin.sokar.wire.TaskMode.SHELL, null,
                    null, null, DEFAULT_MINUTES);
        }

        /**
         * Returns this request for another task name.
         *
         * @param name The task name.
         * @return The request.
         */
        public Request withTask(String name) {
            return new Request(name, projectFile, agentName, providerName, credentialType,
                    tokenHours, upstream, noGate, dryRun, clearance, keep, mode, prompt, model,
                    maxTurns, minutes, repository);
        }
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

    /** How long an unattended agent may run when nothing says otherwise. */
    public static final int DEFAULT_MINUTES = 30;

    /**
     * What {@link #runAgent} answers when there is no agent to run.
     * <p>
     * Distinct from the agent's own failure on purpose: "nothing was installed to do this" and
     * "the agent tried and failed" need different things from whoever reads it, and a caller that
     * collapsed them would send somebody looking through an empty log for a run that never
     * started.
     */
    public static final int NO_AGENT = 69;

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

    /** What to start. Replaced once, when a container name given as the task is taken apart. */
    private Request request;

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
     * Takes a container name given where a task name belongs as the task it names, and says so.
     * <p>
     * Asked by both halves - bringing a task back and creating one - so whichever runs first
     * settles it and the other finds it settled.
     *
     * @param project The project read from the request's file.
     * @param out Where the note is written.
     */
    private void adopt(Project project, PrintWriter out) {
        final String task =
                org.fuin.sokar.runtime.ContainerName.taskFrom(project.name(), request.task());
        if (!task.equals(request.task())) {
            out.println("task      '" + request.task() + "' is the container of task '" + task
                    + "'; starting that");
            out.flush();
            request = request.withTask(task);
        }
    }


    /**
     * A task that was already there, and what bringing it back came to.
     *
     * @param container Container name.
     * @param project Name of the project it belongs to.
     * @param code Exit code: 0 when it is up again, a refusal otherwise.
     * @param action What Start did or refused with, in the contract's words - {@code RESUME}, {@code RUNNING},
     *        {@code NEEDS_VAULT} or {@code PREDATES_RESTART} - or {@code null} for a refusal that has no word there.
     */
    public record Existing(String container, String project, int code, @Nullable String action) {

        /**
         * Constructor for a refusal the contract names no action for.
         *
         * @param container Container name.
         * @param project Project name.
         * @param code Exit code.
         */
        public Existing(String container, String project, int code) {
            this(container, project, code, null);
        }
    }

    /**
     * Brings back the task's container when it already has one.
     * <p>
     * Here rather than in the command, because the daemon starts tasks too: while this lived in
     * {@code TaskRunCommand}, Start over the socket treated a stopped task as a new one, asked
     * which agent to install and never reached the reason it could not come back.
     * <p>
     * A task name maps to exactly one container, so it either exists or it does not. Running is
     * refused rather than started twice - a second container for one task would share the
     * project's mirror and its gate with the first.
     *
     * @param out Where to report.
     * @param err Where to refuse.
     * @return What happened, or {@code null} when there is nothing to bring back and the task has
     *         to be created.
     */
    public @Nullable Existing startExisting(PrintWriter out, PrintWriter err) {

        if (request.dryRun()) {
            // A dry run must touch nothing, and asking the runtime what exists is touching it.
            // The launch says what it would do; the listing's startAction says which case applies.
            return null;
        }
        if (context.hooks().registration()
                != org.fuin.sokar.runtime.HookInstaller.Registration.ACTIVE) {
            // Refused by the launch, and before anything is asked of the runtime: a machine whose
            // hooks are missing cannot start a task either way.
            return null;
        }

        final Project project;
        try {
            project = ProjectReader.read(request.projectFile());
        } catch (RuntimeException ex) {
            // Not this method's refusal to make: the launch reads the same file and says what is
            // wrong with it far better than a guess here would.
            return null;
        }

        adopt(project, out);
        final String container = context.tasks().containerName(project, request.task());
        final java.util.Optional<org.fuin.sokar.runtime.ContainerSummary> summary =
                context.podman().sokarTasks().stream()
                        .filter(found -> found.name().equals(container)).findFirst();
        if (summary.isEmpty()) {
            return null;
        }
        final String theirs = ofAnotherProject(summary.get(), project.name());
        if (theirs != null) {
            err.println("sokar: " + theirs);
            err.flush();
            return new Existing(container, project.name(), 64);
        }
        // A task's repository was fixed when it was created and cannot change, so a caller that
        // names a different one believes something false about work it is about to resume -
        // and would then look for that work where it is not. Ignoring it silently is the one
        // answer that leaves them believing it; the recorded one wins either way, so this says so
        // rather than acting on either.
        final String named = request.repository();
        final String recorded = summary.get().repository();
        if (named != null && !named.isBlank() && recorded != null && !recorded.isBlank()
                && !named.equals(recorded)) {
            err.println("sokar: " + container + " works on '" + recorded + "', not '" + named
                    + "'. A task's repository is fixed when the task is created.");
            err.flush();
            return new Existing(container, project.name(), 64);
        }

        if (summary.get().running()) {
            err.println("sokar: " + container + " is already running");
            err.println("       go into it with 'sokar task attach " + container + "'.");
            err.flush();
            return new Existing(container, project.name(), 65, "RUNNING");
        }

        // Into the project's conversations again: a task that existed before its project named a transport, or before
        // a machine took part in it, was never enrolled, and starting it again left it outside - its messages were
        // queued to a transport that had no account for it. Enrolling keeps what exists, so it is harmless each time;
        // what it cannot do is said, and the task comes back all the same, its workspace being in it.
        final String outside = new TaskConversations(context).enroll(project, container);
        if (outside != null) {
            err.println("sokar: " + outside);
            err.println("       it starts again, outside the conversation; its messages wait until it is in");
            err.flush();
        }
        // A task made before its box opened to its agent, or before agents were told about it, gets both now; and its
        // guide as this version writes it, where its container mounts one.
        try {
            informed(project, container, false);
        } catch (final java.io.IOException ex) {
            err.println("sokar: its mailbox or its guide could not be made ready: " + ex.getMessage());
            err.println("       it starts again; its agent may not reach its mailbox or know about it");
            err.flush();
        }
        // Its workspace, its branch and its uncommitted changes are all in that container. What
        // has to be started again is everything that lives on the host, which is what resume does.
        final TaskControl.Resumed resumed = new TaskControl(context).resume(container);
        return new Existing(container, project.name(),
                TaskResumeCommand.render(resumed, container, out, err, null), switch (resumed.outcome()) {
                    case NEEDS_VAULT -> "NEEDS_VAULT";
                    // Neither can come back; the contract has one word for a task that cannot.
                    case PREDATES_RESTART, TOKENS_NOT_KEPT -> "PREDATES_RESTART";
                    case ALREADY_RUNNING -> "RUNNING";
                    case RESUMED, NO_HELPERS_RECORDED, HELPERS_INCOMPLETE -> "RESUME";
                    default -> null;
                });
    }

    static @Nullable String ofAnotherProject(org.fuin.sokar.runtime.ContainerSummary found, String project) {
        return TaskInventory.ofAnotherProject(found, project);
    }

    /**
     * Says what a task in {@code default} may not be given, and narrows what it would be given by default.
     * <p>
     * {@code default}'s settings are Sokar's and cannot be changed (decided on 2026-10-01): no egress
     * beyond what the agent needs, so no credential for another destination, no other upstream, and no blocked
     * connection let through by a prompt. Whoever needs any of that makes a project repository and follows it.
     *
     * @return Why the start is refused, or {@code null} when it may go ahead.
     */
    private @org.jspecify.annotations.Nullable String defaultRefuses() {
        final String way = "; that is what a project repository is for: write its project.yml and follow it";
        if (!request.credentials().isEmpty()) {
            return "a task in '" + DefaultProject.NAME + "' holds no credential beyond its agent's own" + way;
        }
        if (request.upstream() != null) {
            return "a task in '" + DefaultProject.NAME + "' sends its approved work to its repository's origin and"
                    + " nowhere else" + way;
        }
        if ("prompt".equals(request.clearance()) || "allow".equals(request.clearance())) {
            request = new Request(request.task(), request.projectFile(), request.agentName(), request.providerName(),
                    request.credentialType(), request.tokenHours(), request.upstream(), request.noGate(),
                    request.dryRun(), "deny", request.keep(), request.mode(), request.prompt(), request.model(),
                    request.maxTurns(), request.minutes(), request.repository(), request.credentials());
        }
        return null;
    }

    /**
     * Starts a task and hands the running thing back to the caller.
     *
     * @param out Where progress is reported.
     * @param err Where refusals and failures are reported.
     * @param after What to do once it is up.
     * @return The exit code.
     */
    /** Who is offered a remedy where the start would name a command: nobody, unless a terminal is there. */
    private Offer offer = Offer.NOBODY;

    /**
     * Offers what a refusal would name, through the given helper: at a terminal, a person is asked and the remedy run.
     *
     * @param offer The helper.
     * @return This launch.
     */
    public TaskLaunch offering(final Offer offer) {
        this.offer = offer;
        return this;
    }

    /**
     * Shows what a host this machine never met offers, and records its key when a person says it is the one its owner
     * publishes: a trust decision, so it defaults to no and is never taken by {@code --yes}.
     *
     * @param host The host.
     * @param err Where it is shown and a refusal said.
     * @return Whether the host is known now.
     */
    boolean trustUnmet(final String host, final PrintWriter err) {
        if (offer == Offer.NOBODY) {
            return false;
        }
        final java.util.List<HostKeys.Offered> offered = HostKeys.offeredBy(context, host);
        if (offered.isEmpty()) {
            return false;
        }
        final HostKeys.Offered key = offered.stream().filter(one -> one.type().equals("ssh-ed25519")).findFirst()
                .orElse(offered.get(0));
        err.println("sokar: " + host + " offers:");
        offered.forEach(one -> err.printf("    %-20s %s%n", one.type(), one.fingerprint()));
        err.flush();
        return offer.resolve(new Offer.Remedy("this machine has never met " + host, "Trust its " + key.type() + " key "
                + key.fingerprint() + ", as its owner publishes it?", "sokar credentials trust-host " + host, "", true,
                () -> {
                    try {
                        return HostKeys.trust(context, host, key.fingerprint()) != null;
                    } catch (final java.io.IOException ex) {
                        err.println("sokar: the key could not be recorded: " + ex.getMessage());
                        return false;
                    }
                }, () -> HostKeys.known(context, host)), err);
    }

    public int launch(PrintWriter out, PrintWriter err, AfterStart after) {

        Project project;
        java.nio.file.Path projectFile = request.projectFile();
        try {
            project = ProjectReader.read(projectFile);
            if (DefaultProject.is(project.name())) {
                final String refused = defaultRefuses();
                if (refused != null) {
                    err.println("sokar: " + refused);
                    err.flush();
                    return 2;
                }
                out.println("project        " + DefaultProject.NAME + " - Sokar's settings: guarded, nothing reachable"
                        + " beyond the agent's provider, blocked connections refused");
            }

            // If this machine FOLLOWS a project of that name, the file it verified wins - and the
            // one in the directory is not consulted at all, not preferred and not merged.
            // Following checks a signature against a key pinned out of band; reading anything
            // else afterwards throws that check away, and two sources is how a machine comes to
            // run something nobody chose.
            final ProjectSource.Found found = ProjectSource.resolve(context, project.name());
            if (found.outcome() == ProjectSource.Outcome.FOLLOWED) {
                if (found.file() == null) {
                    // Followed and nothing in force: refused, unreachable, nothing pinned. Falling
                    // back to the local file would run the very thing the machine declined to
                    // apply, which is worse than not starting.
                    err.println("sokar: '" + project.name() + "' is followed and nothing of it is"
                            + " in force, so there is no configuration to start a task with: "
                            + Shown.whyNotInForce(context, project.name()) + ".");
                    err.flush();
                    return 2;
                }
                projectFile = found.file();
                verifiedAt = found.commit();
                project = ProjectReader.read(projectFile);
                // Said as it is: a follow taken without an anchor checked no signature, and calling
                // its commit verified is the one line that tells somebody who decides what this task may reach.
                out.println("source         " + verifiedAt + (found.verified() ? " (followed, verified)"
                        : " (followed, unverified - whoever can push there decides what tasks here may reach)"));
            }

            // Where this project's file is, for an interface that has no filesystem on this
            // machine to find it in. Recorded on every start rather than by a registration step
            // nobody would run.
            new ProjectRegistry(context.paths().projects().projectRegistry())
                    .remember(project.name(), projectFile.toAbsolutePath());
        } catch (ProjectException ex) {
            // A bad project file is the user's problem to fix, not a defect: report it as one
            // line, not as a stack trace.
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        adopt(project, out);
        final java.util.Optional<String> badName =
                org.fuin.sokar.runtime.ContainerName.refusal(project.name(), request.task());
        if (badName.isPresent()) {
            // Before the plan, the dry run and anything written. podman refuses such a name only
            // when the container is created, after the image is built and the policy, resolver
            // and sidecar are on disk - and those stayed behind, named for a task that never was.
            err.println("sokar: " + badName.get());
            err.flush();
            return 64;
        }

        // After the task name and before the image, the plan and the dry run. Which repository the
        // work is for is the first thing about a task, and finding out later means finding out
        // somewhere else; the name is checked first only because a caller who got both wrong
        // should hear about what they typed before what they left out.
        final org.fuin.sokar.core.project.Repository repository;
        try {
            repository = chosenRepository(project);
        } catch (ProjectException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 64;
        }

        out.println("task           " + request.task());
        out.println("project        " + project.name());
        out.println("repository     " + repository.name()
                + (repository.name().equals(project.name()) ? " (the project's own)" : ""));
        out.println("security class " + project.securityClass().name().toLowerCase());
        out.println("base image     " + project.baseImage());
        out.println("task image     " + project.imageName());
        out.flush();

        // Resolved here rather than at the point of use: a set name that does not exist is a
        // mistake in the project file, and the place to report one of those is beside the other
        // project-file errors - before an image is built, and where --dry-run can still see it.
        final java.util.Map<String, String> projectOrigins;
        try {
            projectOrigins = EgressReport.projectEgress(project, repository,
                    context.paths().egress().egressSets());
        } catch (org.fuin.sokar.shield.EgressSetException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }

        if (request.dryRun()) {
            // What the project itself opens. The agent and provider are not chosen yet, so this
            // is a preview of the file rather than the full report a real run prints.
            EgressReport.reportReachable(project, projectOrigins, java.util.Map.of(), out);
            if ((request.repository() == null || request.repository().isBlank())
                    && !project.repositories().isEmpty()) {
                // Said, because otherwise a project-level plan reads as a task's. A repository
                // adds to this, so what a task actually reaches is this or more - never less.
                out.println("note      this is what every repository of '" + project.name()
                        + "' gets; name one with --repository to see what it adds");
            }
            out.flush();
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

        if (!hooksReady(out, err)) {
            return 69;
        }

        final TaskRunner runner = context.tasks();
        final String container =
                runner.containerName(project, request.task());

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

            // Both modes that asked for the agent itself. UNATTENDED because nobody is watching,
            // so a warning is written into an empty room and the failure is found later by
            // somebody who did not start it. AGENT because the person asked for the agent's
            // session: landing them in an agent that cannot authenticate is the refusal below
            // - "the person who asked for an agent finds a bare prompt with nothing explaining
            // why" - one step later, after an image and a container have been built for it.
            //
            // SHELL is deliberately left warning. Working inside the container by hand is exactly
            // what it is for, and whether the agent could authenticate may not matter at all to
            // somebody who is only going to open a terminal.
            //
            // Refused here, before the gate, the image and the container: a refusal that left a
            // workspace and a held container behind would be the warning again with a different
            // exit code.
            // Every other credential the project names, resolved before anything is made: a destination
            // nobody declared is a mistake in the project file, not something to find inside the task.
            final java.util.Map<String, Destination> extras = new java.util.LinkedHashMap<>();
            // A repository at a host this machine never met: refused here, as CanStart answers it. It went ahead
            // before, ssh refused the workspace's fetch, and the task ran without a gate.
            // The address the gate will use: '--upstream' when given, which the project file does not name.
            final String unmet = request.upstream() != null && !request.upstream().isBlank()
                    ? StartCheck.unknownHostOf(context, request.upstream())
                    : StartCheck.unknownHost(context, request.projectFile(), request.repository());
            if (unmet != null && trustUnmet(unmet, err)) {
                out.println("trusted   " + unmet);
                out.flush();
            } else if (unmet != null && offer != Offer.NOBODY) {
                err.println("sokar: nothing was created");
                err.flush();
                return 69;
            } else if (unmet != null) {
                err.println("sokar: this machine has never met " + unmet + ", where the repository is, so it stops"
                        + " rather than deciding for you. See what it offers with 'sokar credentials trust-host "
                        + unmet + "', confirm one against what its owner publishes, and start again.");
                err.println("sokar: nothing was created");
                err.flush();
                return 69;
            }
            // The same words and the same rule CanStart answers with, so a check and a start cannot disagree.
            final String undeclared = StartCheck.undeclared(context, request.projectFile(), request.credentials());
            if (undeclared != null) {
                err.println("sokar: " + undeclared.substring(undeclared.indexOf('\n') + 1));
                err.println("sokar: nothing was created; declare it in "
                        + context.paths().xdg().data().resolve("destinations") + ", or correct what names it");
                err.flush();
                return 69;
            }
            // The run adds to what the project declares, and never takes one away or points it elsewhere.
            final java.util.Map<String, String> declaredOrAdded = new java.util.LinkedHashMap<>(project.credentials());
            for (final java.util.Map.Entry<String, String> added : request.credentials().entrySet()) {
                final String declared = declaredOrAdded.get(added.getKey());
                if (declared != null && !declared.equals(added.getValue())) {
                    err.println("sokar: the project already names credential '" + added.getKey() + "' for '" + declared
                            + "'; a run adds credentials and cannot point one of the project's elsewhere");
                    err.flush();
                    return 2;
                }
                declaredOrAdded.put(added.getKey(), added.getValue());
            }
            // A shut vault is opened here, at a terminal, as 'sokar vault unlock' would: the broker spends its tokens for
            // the task's life. Only where this start reads it; without a terminal the refusals below stay as they were.
            context.openIfShut(!declaredOrAdded.isEmpty() || wiring().unavailableFor(select(agents)) != null, true, err);
            for (final java.util.Map.Entry<String, String> named : declaredOrAdded.entrySet()) {
                final org.fuin.sokar.vault.VaultEntry entry = context.readableCredentials()
                        .map(stored -> stored.get(named.getKey())).orElse(null);
                final Destination destination = Destination.resolve(named.getValue(),
                        Destination.all(context.paths().xdg().data()), context.providers(),
                        entry == null ? null : entry.type());
                if (destination == null) {
                    err.println("sokar: the project names credential '" + named.getKey() + "' for '" + named.getValue()
                            + "', and no destination or provider of that name is declared here");
                    err.println("sokar: nothing was created; declare it in "
                            + context.paths().xdg().data().resolve("destinations") + ", or correct the project file");
                    err.flush();
                    return 69;
                }
                if (request.mode() == org.fuin.sokar.wire.TaskMode.UNATTENDED
                        && CredentialWiring.credentialUnavailable(context.readableCredentials(), named.getKey()) != null) {
                    err.println("sokar: the project names credential '" + named.getKey() + "', and "
                            + CredentialWiring.credentialUnavailable(context.readableCredentials(), named.getKey()));
                    err.println("sokar: nothing was created; an unattended run cannot ask anybody, so it is refused"
                            + " rather than started to fail");
                    err.flush();
                    return 69;
                }
                extras.put(named.getKey(), destination);
            }
            this.extras = java.util.Map.copyOf(extras);

            // A credential that has to be bought is tried once before anything exists: an authorization
            // server that refuses or cannot be reached makes it as missing as a key the vault lacks.
            final java.util.Set<String> held = new java.util.LinkedHashSet<>(extras.keySet());
            final org.fuin.sokar.agent.api.InstalledAgent agentHere = select(agents);
            if (agentHere != null) {
                held.add(credentials().credentialName(agentHere));
            }
            for (final String name : held) {
                final String refused = purchaseRefused(name);
                if (refused == null) {
                    continue;
                }
                if (ungrantedAuthorization(name)) {
                    // Asked of a person too, not only said here: somebody else may be the one who can grant it.
                    AuthorizationsNeeded.raise(context, name, container, project.name(), AuthorizationsNeeded.NEVER);
                }
                if (refusesWithoutCredential(request.mode())) {
                    err.println("sokar: credential '" + name + "': " + refused);
                    err.println("sokar: nothing was created; a task that cannot buy its token would fail on its first"
                            + " request");
                    err.flush();
                    return 69;
                }
                err.println("sokar: credential '" + name + "': " + refused + " - the task starts, and requests that"
                        + " need it will fail until the authorization server sells a token");
                err.flush();
            }

            if (refusesWithoutCredential(request.mode())) {
                final String unavailable = wiring().unavailableFor(select(agents));
                final java.util.List<String> command = unavailable == null || offer == Offer.NOBODY
                        ? java.util.List.of() : signInCommand(select(agents));
                if (unavailable != null && !command.isEmpty() && offer.resolve(credentialRemedy(unavailable, command,
                        () -> wiring().unavailableFor(select(agents)) == null), err)) {
                    out.println("credential " + String.join(" ", command.subList(1, command.size())) + " - in place");
                    out.flush();
                } else if (!command.isEmpty()) {
                    err.println("sokar: nothing was created");
                    err.flush();
                    return 69;
                } else if (unavailable != null) {
                    err.println("sokar: " + unavailable);
                    err.println("sokar: nothing was created; "
                            + (request.mode() == org.fuin.sokar.wire.TaskMode.UNATTENDED
                                    ? "an unattended run cannot ask anybody, so it is refused"
                                            + " rather than started to fail"
                                    : "the agent would start without a credential and fail on its"
                                            + " first request. " + signIn(select(agents))
                                            + "'--attach shell' starts the task without one."));
                    err.flush();
                    return 69;
                }
            }

            // A task asked to open the agent's session must have one. Before the workspace, the
            // image and the container: attaching a plain shell instead and recording the mode as
            // AGENT would say a task is something it is not, and the person who asked for an agent
            // finds a bare prompt with nothing explaining why.
            //
            // Only for AGENT. A SHELL task deliberately needs no agent - working inside the
            // container by hand is exactly what it is for.
            if (request.mode() == org.fuin.sokar.wire.TaskMode.AGENT && select(agents) == null) {
                err.println("sokar: no agent to attach"
                        + (request.agentName() == null ? "; none is installed here"
                                : ": no agent called '" + request.agentName() + "' is installed"));
                err.println("sokar: nothing was created; run with '--attach shell' for a terminal"
                        + " without one");
                err.flush();
                return 69;
            }

            final boolean gated = !request.noGate() && !request.dryRun();
            final TaskWorkspace workspace = workspace().openWorkspace(project, gated, out, err);
            final String earlier = workspace().refusal();
            if (earlier != null) {
                err.println("sokar: " + earlier);
                err.println("sokar: nothing was created");
                err.flush();
                return 65;
            }
            if (gated && workspace == null && request.mode() != org.fuin.sokar.wire.TaskMode.SHELL) {
                // An agent's work leaves through the gate. Started without one, it has an empty workspace and
                // nowhere to hand back what it does - which is how a task in 'default' whose origin was over ssh
                // started, worked on nothing and said exit 0. A shell, which a person drives, still starts.
                err.println("sokar: nothing was created; the agent would work in an empty workspace with nowhere to"
                        + " hand its work back. Fix what is said above, or '--attach shell' starts a shell without"
                        + " a gate");
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

            // Before anything exists: a model the agent has no flag for would be dropped on the way in,
            // attended or not, and the agent would answer on its own default without a word.
            if (request.model() != null && selected != null && selected.definition().headless().modelFlag() == null) {
                err.println("sokar: " + selected.name() + " does not take a model, so --model " + request.model()
                        + " would change nothing; start it without --model");
                err.flush();
                return 2;
            }

            // Into the project's conversations before anything exists, and after every other refusal: a task whose
            // messages reach nobody is refused here, as a credential nobody can reach is - and enrolled earlier, a start
            // refused afterwards for something else left its account at the transport with nothing to retire it.
            final String unreachable = new TaskConversations(context).enroll(project, container);
            if (unreachable != null) {
                err.println("sokar: " + unreachable);
                err.println("sokar: nothing was created");
                err.flush();
                return 69;
            }

            // The state directory has to exist before anything writes into it. start() also
            // creates it, but the credential proxy needs it first: it writes its socket, its
            // token and its pid there, and it has to be listening before the container exists.
            final java.nio.file.Path state = context.paths().tasks().containerState(container);
            java.nio.file.Files.createDirectories(state);

            // The mailbox, which is not in the state directory and does not go with it: a
            // conversation survives stop, start and a machine restart, and only 'remove' ends it.
            final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
            mailbox.create();
            informed(project, container, true);

            TaskWiring wiring = new TaskWiring(
                    workspace == null ? null : gate().gateAddress(project, err),
                    workspace == null ? 0 : workspace.port(), null, mailbox.box());

            java.util.Map<String, String> environmentCache = new java.util.LinkedHashMap<>();

            // An agent that can only address a URL still gets the broker on its socket: only the
            // listening end moves into the container's namespace, and that is a relay started
            // after the container exists.
            final SelectedProvider serving = credentials().provider(selected);
            servingName = serving == null ? null : serving.name();
            // Every destination carries where it came from, so the report at the end of this can
            // answer "who decided this" rather than only "what is open". The same composition the
            // egress editor shows, from the same method.
            final EgressReport.Reachable reachable =
                    EgressReport.compose(selected, serving, projectOrigins);
            final java.util.List<String> domains = new java.util.ArrayList<>(reachable.domains());
            final java.util.Map<String, String> origins =
                    new java.util.LinkedHashMap<>(reachable.origins());

            final CredentialWiring.CredentialPlumbing plumbing =
                    wiring().startVault(selected, container, this.extras, out, err);
            if (!this.extras.isEmpty()) {
                // By name and destination, for anything that asks what the task holds; never a token.
                final java.util.Map<String, Object> given = new java.util.LinkedHashMap<>();
                this.extras.forEach((name, destination) -> given.put(name, destination.name()));
                try {
                    java.nio.file.Files.writeString(context.paths().tasks().containerState(container)
                            .resolve(TaskInventory.CREDENTIALS_FILE), org.fuin.sokar.wire.Json.write(given),
                            java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException ex) {
                    err.println("sokar: could not record which credentials this task holds: " + ex.getMessage());
                    err.flush();
                }
                GrantRecord.record(context, container, project.name(), this.extras.keySet(), err);
            }
            // The agent's URL endpoint, or any other credential: those are always reached over the URL.
            final boolean needsRelay = serving != null
                    && serving.route().endpoint()
                            == org.fuin.sokar.agent.api.ProviderRoute.Endpoint.URL
                    || plumbing != null && plumbing.servesOthers();
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

            // The issue prefixes the task's repository owns, so its agent names and cites issues as the repository
            // does; empty where the repository names none.
            environmentCache.put(ISSUE_PREFIXES, String.join(",", repository.issues()));
            if (workspace != null) {
                environmentCache.putAll(workspace.environment(project, request.task()));
            }

            final java.util.Map<String, String> refusals = EgressReport.refusals(selected, project, repository);
            EgressReport.reportReachable(project, origins, refusals, out);
            runner.refusing(java.util.List.copyOf(refusals.keySet()));

            // The port is decided before this, so the firewall rule can name it; the gate itself
            // starts afterwards, because its log lives in the state directory that start()
            // creates. Starting it first silently failed to spawn at all.
            runner.start(project, repository, verifiedAt, container, layers, environmentCache,
                    domains, wiring, out);

            if (needsRelay && plumbing != null) {
                wiring().startRelay(runner, container, plumbing.socket(), out, err);
            }

            if (workspace != null) {
                gate().startGate(runner, workspace, wiring.gateAddress(), container, request.task(), project, out, err);
                workspace().prepareWorkspace(runner, workspace, container, environmentCache, out, err);
            }

            placeAgentFiles(runner, selected, container, environmentCache, out, err);
            out.println();

            clearance().startClearance(runner, project, container, out, err);
            new BuildsWiring(context, this::recordHelper).start(project, repository, container,
                    environmentCache.get("SOKAR_TASK_REF"), out, err);
            writeResumeRecord(container, err);
            // What only this moment knows: which agent, how somebody is meant to be involved,
            // what it was asked to do, and which ref its work goes to. None of it can be
            // recovered from a running container afterwards.
            writeProfile(container, selected, environmentCache.get("SOKAR_TASK_REF"), err);
            // What brings it back after a reboot: its description and records saved where a reboot does not
            // reach, and its two tokens in the vault. A task that cannot keep them runs, and says so.
            new TaskState(context).save(container);
            final String unkept = new TaskSecrets(context).keep(container,
                    TaskSecrets.fromRuntime(context.paths().tasks().containerState(container)), existingTasks(container));
            if (!unkept.isEmpty()) {
                out.println("reboot    " + unkept);
            }

            // The task is up. What happens next - run the agent to completion, attach a
            // terminal, or simply say so - is the caller's, and it happens inside this scope
            // on purpose: the installed agents are a resource this owns, and the paths that
            // follow a start use them.
            return after.started(new Running(runner, agents, selected, container,
                    environmentCache, project, code -> cleanUp(runner, container, code, out)));

        } catch (CommandException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return cleanUp(runner, container, 70, out);
        } catch (org.fuin.sokar.agent.api.AgentException ex) {
            // "Several agents are installed" is a sentence for a person. Printed as an object it
            // reached an interface as a Java class name, which says nothing about what to do.
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return cleanUp(runner, container, 69, out);
        } catch (Exception ex) {
            err.println("sokar: " + ex);
            err.flush();
            return cleanUp(runner, container, 70, out);
        }
    }

    /**
     * Runs the agent against the prompt this task was started with.
     * <p>
     * In the launch rather than in the CLI, because a task started over the socket is as
     * unattended as one started at a terminal and has to do the same thing. What stays in the CLI
     * is the rendering: the agent formats its own log for a person, and a socket has no person on
     * the far end - only the file, which {@code Tail} already serves.
     *
     * @param runner Runs the container.
     * @param selected The agent, or {@code null} when none is installed.
     * @param container Container name.
     * @param environment What the container was given, so the agent runs with the same.
     * @param out Where progress is reported.
     * @param err Where a failure is reported.
     * @return Exit code of the agent, or 69 when there is no agent to run.
     */
    public int runAgent(TaskRunner runner, @Nullable InstalledAgent selected, String container,
            java.util.Map<String, String> environment, PrintWriter out, PrintWriter err) {

        if (selected == null) {
            err.println("sokar: a prompt needs an agent, and none is installed");
            err.flush();
            return NO_AGENT;
        }
        final String prompt = request.prompt();
        if (prompt == null) {
            err.println("sokar: this task was started without a prompt, so there is nothing to run");
            err.flush();
            return 2;
        }
        final Path log = context.paths().tasks().containerState(container).resolve("task.log");
        // Continuing is what starting a task that has a session does - not a verb of its own - and only
        // where the agent says how to name one: nothing is guessed, and a fresh session says it is one.
        final TaskSession sessions = new TaskSession(context);
        final String continuing = sessions.toContinue(container, selected.definition()).orElse(null);
        final org.fuin.sokar.agent.api.RunRequest agentRequest =
                new org.fuin.sokar.agent.api.RunRequest(prompt, request.model(),
                        request.maxTurns(), continuing, false, true);

        out.println();
        out.println("running   " + selected.name() + " (up to " + request.minutes() + " minutes)");
        out.println("session   " + (continuing != null ? "continuing " + continuing
                : selected.definition().sessionIds() == null ? "a fresh one - this agent does not say how to continue one"
                : "a fresh one"));
        out.flush();

        try {
            return runner.runAgent(selected, container, agentRequest, environment, log,
                    java.time.Duration.ofMinutes(request.minutes()));
        } catch (org.fuin.sokar.runtime.ContainerException ex) {
            // Whatever the agent managed to say before it was killed is the most useful thing
            // there is at this point. Throwing here would discard it, which is the opposite of
            // what someone diagnosing a stuck run needs.
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 124;
        } finally {
            // Whatever the run named, even one that was killed: the transcript up to there is worth going on from.
            final org.fuin.sokar.agent.api.SessionIds ids = selected.definition().sessionIds();
            if (ids != null && selected.definition().supportsResume()) {
                TaskSession.fromRun(log, ids).ifPresent(id -> sessions.record(container, id));
            }
        }
    }

    /**
     * Runs the prompt in a task that existed already and is up again, with the agent it was made with.
     * <p>
     * <strong>Continuing is what starting a task with a prompt does</strong>, whether the task is new or
     * comes back: a start that brought a stopped task back and then ignored its prompt reported a run nobody
     * performed. The agent is the task's own, not whatever a caller names - a conversation belongs to the
     * agent that had it - and the run continues its session where the agent can ({@link #runAgent}).
     *
     * @param container The task's container, running.
     * @param rendered What shows the run to a person afterwards, given the agent; the daemon passes nothing.
     * @param out Where progress is reported.
     * @param err Where a failure is reported.
     * @return Exit code of the agent, or {@link #NO_AGENT} when its agent is not installed.
     */
    public int runAgentInExisting(String container,
            java.util.function.@Nullable BiConsumer<InstalledAgent, String> rendered, PrintWriter out, PrintWriter err) {
        final org.fuin.sokar.wire.TaskProfile profile =
                org.fuin.sokar.wire.TaskProfile.readFrom(context.paths().tasks().containerState(container));
        try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
            final InstalledAgent selected = profile == null || profile.agent() == null ? null
                    : agents.find(profile.agent()).orElse(null);
            // Nothing extra for the exec: a container that exists already carries what it was given.
            final int code = runAgent(context.tasks(), selected, container, java.util.Map.of(), out, err);
            if (code != NO_AGENT && rendered != null && selected != null) {
                rendered.accept(selected, container);
            }
            return code;
        }
    }

    /**
     * Writes down what this task is, for anything that asks about it later.
     * <p>
     * Never fails the run. A task that cannot describe itself is worse than one that can, and far
     * better than one that did not start.
     *
     * @param container Container name.
     * @param selected The agent, or {@code null} when the task has none.
     * @param branch Ref the work goes to, or {@code null}.
     * @param err Where a failure to record is reported.
     */
    private void writeProfile(String container, @Nullable InstalledAgent selected,
            @Nullable String branch, PrintWriter err) {
        try {
            new org.fuin.sokar.wire.TaskProfile(org.fuin.sokar.wire.TaskProfile.VERSION,
                    selected == null ? null : selected.definition().name(),
                    request.mode(), request.prompt(), branch,
                    java.time.Instant.now().toString(), request.clearance(), null, servingName)
                    .writeTo(context.paths().tasks().containerState(container));
        } catch (java.io.IOException ex) {
            err.println("sokar: could not record what this task is: " + ex.getMessage());
            err.flush();
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
        final Project project = ProjectReader.read(request.projectFile());
        // A key this Sokar does not know and that is no provable mistake - a later version's, most likely - is
        // taken and said, so nobody believes it applied.
        for (final String unknown : ProjectReader.unknownKeys(request.projectFile())) {
            err.println("warning   project.yml: '" + unknown + "' is not a setting this Sokar knows; it has no effect");
        }
        err.flush();
        return project;
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

    @Nullable
    private InstalledAgent select(InstalledAgents agents) {
        final String named = request.agentName() != null || offer == Offer.NOBODY ? request.agentName()
                : agentName(agents.all().stream().map(InstalledAgent::name).toList(), null, offer, chosenAgent);
        return select(agents, named);
    }

    /** The agent a person chose when several were installed and none was named: asked once per start. */
    private final String[] chosenAgent = new String[1];

    /**
     * Returns the agent a start works with: the one named, else the one a person chooses when several are installed,
     * asked once and kept for the rest of the start.
     *
     * @param installed The installed agents' names.
     * @param named The one named, or {@code null}.
     * @param offer Who is asked.
     * @param chosen Where the choice is kept for this start.
     * @return The name, or {@code null} to leave it to the agent choice, which takes the only one or refuses.
     */
    static @Nullable String agentName(final java.util.List<String> installed, final @Nullable String named,
            final Offer offer, final @Nullable String[] chosen) {
        if (named != null) {
            return named;
        }
        if (chosen[0] == null && installed.size() > 1) {
            chosen[0] = offer.choose("several agents are installed (" + String.join(", ", installed) + ")", installed,
                    "sokar task start --agent <name>");
        }
        return chosen[0];
    }

    /**
     * Whether a mode is refused outright when the agent's credential cannot be had.
     * <p>
     * The two that asked for the agent itself. {@code SHELL} is not one of them: working inside
     * the container by hand is what it is for, and whether the agent could authenticate may not
     * matter to it at all - so it warns and starts, which is what every mode used to do.
     * <p>
     * Named rather than inlined because it is a decision, taken on 2026-09-12
     * after a locked vault let an {@code AGENT} task build an image and a container and then land
     * somebody in an agent that could not authenticate.
     *
     * @param mode The mode asked for.
     * @return Whether a credential that cannot be had refuses the start.
     */
    static boolean refusesWithoutCredential(org.fuin.sokar.wire.TaskMode mode) {
        return mode == org.fuin.sokar.wire.TaskMode.UNATTENDED
                || mode == org.fuin.sokar.wire.TaskMode.AGENT;
    }

    @Nullable
    static InstalledAgent select(InstalledAgents agents, @Nullable String agentName) {
        return AgentChoice.select(agents, agentName);
    }

    /**
     * Picks the agent a command should work with, without throwing.
     *
     * @param agents What is installed.
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @return The choice, refused or not.
     */
    public static AgentChoice.Choice considerAgent(InstalledAgents agents, @Nullable String agentName) {
        return AgentChoice.considerAgent(agents, agentName);
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
        final String named = variable == null ? null : environment.get(variable);
        // An empty token rather than an early return. Two of the three files Claude Code declares
        // - onboarding done, this folder is trusted - have nothing to do with a credential, and
        // returning here withheld those too: somebody who starts a task without one and logs in
        // inside it met every dialog this requirement exists to remove. What to write without a
        // token is the agent's own decision, made against a blank one, not Sokar's to infer.
        final String token = named == null ? "" : named;
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
     * Makes sure podman will run Sokar's hooks, repairing what a command may repair.
     * <p>
     * <strong>Two of the four states are just "write the files".</strong> A user who has never run
     * {@code sokar setup}, and one whose descriptors an upgrade left behind, were both told to go
     * and run a command - and being told to run a command is a thing people forget, which is how a
     * machine ends up with an installation nobody completed. Doing it here costs nothing and
     * removes the remembering.
     * <p>
     * <strong>Why this is not the package's job.</strong> podman reads hook descriptors per user,
     * so an install script running as root does not know whose configuration to write, and putting
     * a {@code hooks_dir} in the system configuration would point every user's podman at Sokar -
     * which is the {@code SHADOWED} offence Sokar refuses to tolerate in other people's drop-ins.
     * Here it runs as the user, in their own configuration, because they asked for a task.
     * <p>
     * <strong>Why the other two still refuse.</strong> Missing binaries are a broken installation
     * and writing descriptors would not fix them; a drop-in of somebody else's that sorts later is
     * theirs, and Sokar does not get to delete or reorder it. And the repair is verified rather
     * than assumed: what podman will do is asked again afterwards, because installing cannot rule
     * either of those out.
     *
     * @param out Where a repair is reported.
     * @param err Where a refusal is reported.
     * @return {@code true} if a task may start.
     */
    private boolean hooksReady(PrintWriter out, PrintWriter err) {

        final org.fuin.sokar.runtime.HookInstaller installer = context.hooks();
        org.fuin.sokar.runtime.HookInstaller.Registration hooks = installer.registration();

        if (hooks == org.fuin.sokar.runtime.HookInstaller.Registration.MISSING
                || hooks == org.fuin.sokar.runtime.HookInstaller.Registration.STALE) {
            final boolean first =
                    hooks == org.fuin.sokar.runtime.HookInstaller.Registration.MISSING;
            try {
                // Said out loud rather than done quietly: this writes into the operator's own
                // podman configuration, and somebody who removed the hooks on purpose should see
                // them come back rather than discover it later.
                final java.util.List<java.nio.file.Path> written = installer.install();
                out.println("hooks     " + (first ? "registered with podman"
                        : "brought up to date") + ", " + written.size() + " files");
                out.flush();
            } catch (java.io.IOException ex) {
                err.println("sokar: the hooks could not be registered with podman: "
                        + ex.getMessage() + " - try 'sokar setup'");
                err.flush();
                return false;
            }
            hooks = installer.registration();
        }

        if (hooks == org.fuin.sokar.runtime.HookInstaller.Registration.ACTIVE) {
            return true;
        }

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
                    + installer.effectiveHooksDirectories()
                    + ", so Sokar's hooks would not run";
            // Names the files, because "run setup again" after an upgrade that changed
            // nothing visible reads like superstition until you can see what differs.
            case STALE -> "sokar: the installed hook files are from a different version of"
                    + " Sokar, run 'sokar setup' again - " + installer.outdated();
            case ACTIVE -> "";
        });
        err.flush();
        return false;
    }

    /**
     * Writes down what this run started, so the task can be resumed after it is stopped.
     *
     * @param container Container name.
     * @param err Where a failure is reported.
     */
    private java.util.Set<String> existingTasks(String container) {
        final java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add(container);
        context.podman().sokarTasks().forEach(task -> existing.add(task.name()));
        return existing;
    }

    private void writeResumeRecord(String container, PrintWriter err) {
        if (startedHelpers.isEmpty()) {
            return;
        }
        try {
            new TaskHelpers(java.util.List.copyOf(startedHelpers))
                    .writeTo(context.paths().tasks().containerState(container));
        } catch (java.io.IOException ex) {
            // The task runs regardless; only resuming it later is lost.
            err.println("sokar: could not record how to resume this task: " + ex.getMessage());
            err.flush();
        }
    }

    /**
     * Says how to sign in, for an agent that has a sign-in of its own: the way out a person is told first, because the
     * other one - a shell - is where an agent with no credential says "Not logged in".
     *
     * @param agent The agent, or {@code null}.
     * @return The sentence, ending in a space, or "".
     */
    private java.util.List<String> signInCommand(@Nullable InstalledAgent agent) {
        final SelectedProvider selection = agent == null ? null : credentials().provider(agent);
        final org.fuin.sokar.agent.api.ProviderDefinition provider =
                selection == null ? null : context.providers().get(selection.name());
        return nextCommand(agent == null ? null : agent.name(),
                agent != null && agent.definition().loginArguments() != null, provider,
                agent == null ? "" : credentials().credentialName(agent));
    }

    private String signIn(@Nullable InstalledAgent agent) {
        final SelectedProvider selection = agent == null ? null : credentials().provider(agent);
        final org.fuin.sokar.agent.api.ProviderDefinition provider =
                selection == null ? null : context.providers().get(selection.name());
        return nextStep(agent == null ? null : agent.name(), agent != null && agent.definition().loginArguments() != null,
                provider, agent == null ? "" : credentials().credentialName(agent));
    }

    /**
     * Returns the one command that gets a task its credential, said where a start is refused for want of it.
     *
     * @param agent The agent's name, or {@code null} when none is installed.
     * @param logsIn Whether the agent declares a login of its own.
     * @param provider The provider chosen, or {@code null}.
     * @param credential The vault entry the credential would be under.
     * @return The sentence, ending in "; ", or empty when there is nothing to name.
     */
    static String nextStep(@Nullable String agent, boolean logsIn,
            org.fuin.sokar.agent.api.@Nullable ProviderDefinition provider, String credential) {
        final java.util.List<String> command = nextCommand(agent, logsIn, provider, credential);
        if (command.isEmpty()) {
            return "";
        }
        final String said = "'sokar " + String.join(" ", command) + "'";
        return switch (command.get(1)) {
            case "authorize" -> "Grant it first with " + said + "; ";
            case "login" -> "Sign in first with " + said + "; ";
            default -> "Store its key first with " + said + "; ";
        };
    }

    /**
     * Returns the one command that gets a task its credential, as {@link #nextStep} says it, as arguments.
     *
     * @param agent The agent's name, or {@code null} when none is installed.
     * @param logsIn Whether the agent declares a login of its own.
     * @param provider The provider chosen, or {@code null}.
     * @param credential The vault entry the credential would be under.
     * @return Its arguments after {@code sokar}, or empty when there is nothing to name.
     */
    static java.util.List<String> nextCommand(@Nullable String agent, boolean logsIn,
            org.fuin.sokar.agent.api.@Nullable ProviderDefinition provider, String credential) {
        if (provider != null && !provider.grant().isEmpty()) {
            return java.util.List.of("vault", "authorize", provider.name());
        }
        if (agent != null && logsIn) {
            return java.util.List.of("vault", "login", agent);
        }
        return credential.isEmpty() ? java.util.List.of() : java.util.List.of("vault", "put", credential);
    }

    /**
     * Returns the remedy for a credential a start lacks: the command a refusal would name, run on this terminal - its
     * own code, which asks a key without echo or runs the agent's sign-in - and the credential looked for again after.
     *
     * @param unavailable What is missing, as the refusal says it.
     * @param command The command's arguments after {@code sokar}.
     * @param holds Whether the credential is there.
     * @return The remedy.
     */
    Offer.Remedy credentialRemedy(final String unavailable, final java.util.List<String> command,
            final java.util.function.BooleanSupplier holds) {
        final String said = "sokar " + String.join(" ", command);
        return new Offer.Remedy(unavailable, "Run '" + said + "' now?", said, "", false,
                () -> context.exec().applyAsInt(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(SokarBinary.path()), command.stream()).toList()) == 0, holds);
    }

    private CredentialChoice credentials() {
        if (credentials == null) {
            credentials = new CredentialChoice(context, request.providerName(), request.credentialType(),
                    request.agentName()).offering(offer);
        }
        return credentials;
    }

    /**
     * Tries to buy a token with a credential of the kind the broker buys with, once, before anything exists.
     *
     * @param name The credential's vault entry.
     * @return Why it could not be bought, or {@code null} when it could or is not of that kind.
     */
    private boolean ungrantedAuthorization(String name) {
        final org.fuin.sokar.vault.VaultEntry entry = context.readableCredentials()
                .map(stored -> stored.get(name)).orElse(null);
        return entry != null && org.fuin.sokar.supervisor.Grants.isGrant(entry.type());
    }

    private @Nullable String purchaseRefused(String name) {
        final org.fuin.sokar.vault.VaultEntry entry = context.readableCredentials()
                .map(stored -> stored.get(name)).orElse(null);
        if (entry != null && org.fuin.sokar.supervisor.Grants.isGrant(entry.type())) {
            // Only whether a person has granted it: spending it here could rotate it under the broker.
            final boolean granted = context.opener().map(opener -> context.vault().read(opener)
                    .containsKey(TaskSecrets.GRANT_PREFIX + name)).orElse(false);
            return granted ? null : "nobody has granted it yet; a person authorizes it once with 'sokar vault authorize "
                    + name + "'";
        }
        if (entry == null || !org.fuin.sokar.supervisor.TokenPurchase.KIND.equals(entry.type())) {
            return null;
        }
        try {
            new org.fuin.sokar.supervisor.TokenPurchase(
                    org.fuin.sokar.supervisor.TokenPurchase.Client.of(entry.value(), entry.settings()),
                    org.fuin.sokar.core.net.TrustedCertificates.builder(org.fuin.sokar.core.net.TrustedCertificates.file(context.paths().xdg())).connectTimeout(java.time.Duration.ofSeconds(30))
                            .followRedirects(java.net.http.HttpClient.Redirect.NEVER).build(),
                    java.time.Clock.systemUTC()).current();
            return null;
        } catch (IllegalArgumentException ex) {
            return ex.getMessage();
        } catch (org.fuin.sokar.supervisor.TokenPurchase.Refused ex) {
            return ex.getMessage();
        }
    }

    /** The project's other credentials, resolved when the task is launched; empty until then. */
    private java.util.Map<String, Destination> extras = java.util.Map.of();

    private CredentialWiring wiring() {
        if (wiring == null) {
            wiring = new CredentialWiring(context, credentials(), this::recordHelper, request.task(),
                    request.tokenHours(), request.upstream());
        }
        return wiring;
    }

    private GateWiring gate() {
        if (gate == null) {
            gate = new GateWiring(context, this::recordHelper, request.projectFile(),
                    request.upstream(), request.repository());
        }
        return gate;
    }

    /**
     * Returns the earlier work of this task's name that waits at the gate, when the start was refused for it.
     *
     * @return The work, or {@code null}.
     */
    public org.fuin.sokar.gate.GitGate.@Nullable Waiting earlierWork() {
        return workspace == null ? null : workspace.earlierWork();
    }

    private WorkspaceSetup workspace() {
        if (workspace == null) {
            workspace = new WorkspaceSetup(context, request.task(), request.upstream(),
                    request.repository());
        }
        return workspace;
    }

    /**
     * Returns the repository this task works on, refusing a request that names none.
     * <p>
     * <strong>There is no case where Sokar picks</strong>, not even when the project has exactly
     * one. A default would be paid for later and somewhere else: a project that grows a second
     * repository would silently change what an existing command does, and a command whose meaning
     * depends on how many repositories exist today is one nobody can read. Naming it is one word.
     * <p>
     * A task with no gate is the exception, and not really one: it gets an empty directory and
     * cannot commit anywhere, so there is no repository for it to be about.
     *
     * @param project The project.
     * @return The repository.
     * @throws ProjectException When none was named, or none of that name exists. The message
     *         names what there is to choose from.
     */
    private org.fuin.sokar.core.project.Repository chosenRepository(Project project) {
        if (request.repository() == null || request.repository().isBlank()) {
            if (request.noGate() || request.dryRun()) {
                // A dry run starts nothing, so there is no task to start on a guessed repository -
                // which is the only thing the refusal below protects. What it reports without one
                // is the project's own repository, and that is not a pick: its egress and limits
                // ARE the project's block, so the answer is the project-level plan, true of every
                // repository. A repository of its own adds to it, which is why naming one shows
                // more rather than something else.
                return project.ownRepository();
            }
            // Ready to paste, because the first run of all is the one that cannot know the
            // answer: the wizard writes the project file moments earlier, and being told what
            // there is to choose from without being told how to say it would make the
            // improvement into an obstacle.
            throw new ProjectException("say which repository this task is for."
                    + System.lineSeparator() + "       '" + project.name() + "' works in: "
                    + String.join(", ", project.workRepositoryNames())
                    // No task name in it: an unnamed task is named after the repository it gets, and the
                    // placeholder it carries until then is nothing a person chose.
                    + System.lineSeparator() + "       sokar task start --repository "
                    + project.workRepositoryNames().get(0));
        }
        if (OwnRepository.refused(project, request.repository())) {
            throw new ProjectException(OwnRepository.refusal(project));
        }
        return GateSupport.repository(project, request.repository());
    }

    private ClearanceWiring clearance() {
        if (clearanceWiring == null) {
            clearanceWiring = new ClearanceWiring(context, this::recordHelper, request.task(), request.clearance());
        }
        return clearanceWiring;
    }

    /**
     * The commit this task's configuration was applied at, or "" for a project nobody follows. Whether a
     * signature was checked on it is the follow's, and {@link ProjectSource.Found#verified()} says which.
     * <p>
     * A field rather than a local, because the start that labels the container happens inside a
     * scope that captures it - and it is written once, where the project is resolved.
     */
    private String verifiedAt = "";

    /** What this run started on the host, written out so a later start can bring them back. */
    private final java.util.List<TaskHelpers.Helper> startedHelpers = new java.util.ArrayList<>();

    /**
     * Which credential this task uses, and what to say about it. Built on first use because it
     * reads the options, and those are not set until picocli has parsed them.
     */
    private @Nullable CredentialChoice credentials;

    /** The provider this task was brokered to, once settled, for its profile. */
    private @Nullable String servingName;

    /** The broker, the relay and the signing agent this task needs. Built on first use. */
    private @Nullable CredentialWiring wiring;

    /** How the git gate is bound and firewalled for this task. Built on first use. */
    private @Nullable GateWiring gate;

    /** The repository the agent works in. Built on first use. */
    private @Nullable WorkspaceSetup workspace;

    /** The watcher that asks about blocked connections. Built on first use. */
    private @Nullable ClearanceWiring clearanceWiring;

    /**
     * Removes a task that finished, and holds one that failed.
     * <p>
     * <strong>A failure is kept, because nobody can ask for that in advance.</strong> {@code
     * --keep} has to be decided before the run, and the run an operator wants to look at is the
     * one that went wrong - which is known only afterwards. So a non-zero exit stops the container
     * rather than removing it: the workspace, the logs and anything that never reached the gate
     * stay where they are, {@code task attach} offers to bring it back, and discarding it is a
     * separate decision made by name.
     * <p>
     * Stopped through {@link TaskControl}, not by leaving it running: a task nobody is watching
     * that still holds a firewall, a gate and a credential proxy is not "kept", it is abandoned.
     * That is also what writes down what the workspace held.
     *
     * @param runner Runs the container.
     * @param container Container name.
     * @param code Exit code of the run.
     * @param out Where the decision is reported.
     * @return The exit code, unchanged.
     */
    private int cleanUp(TaskRunner runner, String container, int code, PrintWriter out) {
        try {
            return cleanUpOrFail(runner, container, code, out);
        } catch (final RuntimeException ex) {
            // Said, never in its place: what failed while cleaning up replaced the refusal that caused the clean-up,
            // and a start refused for its agent answered over the socket that its container was no task Sokar made.
            out.println("sokar: cleaning up after it failed: " + ex.getMessage());
            out.flush();
            return code;
        }
    }

    private int cleanUpOrFail(TaskRunner runner, String container, int code, PrintWriter out) {

        final boolean exists = context.podman().idOf(container).isPresent();

        if (code != 0 && exists && !request.keep()) {
            final TaskControl.Stopped held = new TaskControl(context).stop(container);
            out.println("kept      " + container + " - it failed, so nothing was removed");
            if (held.work() != null) {
                out.println("          it holds " + held.work());
            }
            out.println("          look with 'sokar task list', go back in with"
                    + " 'sokar task attach " + container + "'");
            out.println("          - which offers to start it again -");
            out.println("          discard with 'sokar task remove " + container + "'");
            out.flush();
        } else if (!request.keep()) {
            // The same question 'task remove' asks before it removes anything. A workspace
            // is in the container's own writable layer, so this remove destroys it - and asking
            // there but not here was the difference between refusing to discard unreviewed work
            // and discarding it because somebody typed 'exit'. Only answerable while the
            // container runs, which it still is: the shell was an exec beside 'sleep infinity'.
            final String held = exists && context.podman().pidOf(container).orElse(0L) > 0
                    ? new TaskControl(context).heldBy(container).phrase() : null;
            if (held == null) {
                // The ordinary ending, as 'task remove' ends a task: the container, and its account in the project's
                // conversations, its tokens and its mailbox with it. Removed with the container alone, the account
                // stayed at the transport, and a prune later forgot the only credential that could retire it.
                if (exists) {
                    new TaskControl(context).remove(container, false, true);
                } else {
                    runner.remove(container);
                }
            } else {
                // Stopped rather than left up, for the reason the failed branch is: a task
                // nobody is watching that still holds a firewall, a gate and a credential proxy
                // is not kept, it is abandoned.
                new TaskControl(context).stop(container);
                out.println("kept      " + container + " - it holds " + held
                        + " that never reached the gate");
                out.println("          go back in with 'sokar task attach " + container + "',"
                        + " which offers to start it again,");
                out.println("          push it with 'sokar task remove " + container + " --rescue'"
                        + " while it runs,");
                out.println("          or discard it with 'sokar task remove " + container
                        + " --force'");
                out.flush();
            }
        }
        // Only reaps when no container is running: a start that failed fires no poststop hook.
        runner.reapOrphans(container);
        return code;
    }

    /**
     * Makes a task's mailbox as it is now, tells its agent how it works and whom it can reach, and writes the guide to
     * all of Sokar in the task.
     * <p>
     * Before its agent starts: an agent takes the guide in when it starts, and a missing one is never added to its
     * command. The guide's directory is made only with the task, before its container, which mounts it; a task made
     * before has no mount for it, and a directory made now would point its agent at a file it cannot see.
     *
     * @param project The task's project.
     * @param container The task.
     * @param created Whether the task is being made now, rather than started again.
     * @throws java.io.IOException Writing failed.
     */
    private void informed(final Project project, final String container, final boolean created)
            throws java.io.IOException {
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        final TaskGuide guide = new TaskGuide(context.paths(), container);
        if (created || guide.exists()) {
            TaskGuideText.write(guide, mailbox.exists());
        }
        if (!mailbox.exists()) {
            return;
        }
        mailbox.create();
        MailboxGuide.write(mailbox, new MessageWatch(context, java.time.Duration.ZERO).withSiblings(project, container));
    }
}
