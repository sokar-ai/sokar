package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.hardening.ProcessHardening;
import org.fuin.sokar.wire.SocketContext;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.shield.DnsmasqProbe;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

/**
 * Reports what the binary can see of its own environment.
 * <p>
 * Useful on its own, and it keeps the hardening downcall reachable from the shipped binary, so a
 * missing FFM registration would surface here rather than in the middle of starting a container.
 */
@Command(name = "doctor",
        mixinStandardHelpOptions = true,
        description = "Reports paths and process hardening state.")
public class DoctorCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @picocli.CommandLine.Option(names = "--color", paramLabel = "<when>",
            description = "auto, always or never. Default: ${DEFAULT-VALUE} - colour on a terminal unless NO_COLOR is"
                    + " set.")
    private String color = "auto";

    /** Whether this report is coloured, decided once per run. */
    private boolean colour;

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    /**
     * Returns whether the report is coloured: as the command says, else on a terminal unless {@code NO_COLOR} is set.
     *
     * @param mode {@code auto}, {@code always} or {@code never}.
     * @param terminal Whether standard output is a terminal.
     * @param noColor {@code NO_COLOR}, or {@code null} when it is not set; set at all, even empty, it means none.
     * @return Whether to colour.
     */
    static boolean colours(final String mode, final boolean terminal,
            final @org.jspecify.annotations.Nullable String noColor) {
        return switch (mode) {
            case "always" -> true;
            case "never" -> false;
            default -> terminal && noColor == null;
        };
    }

    /**
     * Returns a line's start in its state's colour: a failure red, a warning yellow, what is fine green. Only the colour
     * is added; the words are what a reader without colour reads.
     *
     * @param text What to colour.
     * @param state What the probe found.
     * @param colour Whether this report is coloured.
     * @return The text, coloured or as it was.
     */
    static String painted(final String text, final Probe.State state, final boolean colour) {
        if (!colour) {
            return text;
        }
        final String code = switch (state) {
            case MISSING -> "31";
            case DEGRADED, UNKNOWN -> "33";
            case OK -> "32";
        };
        return "\u001b[" + code + "m" + text + "\u001b[0m";
    }

    /**
     * Reports whether the installed dnsmasq can populate the firewall's allow set.
     * <p>
     * Without {@code --nftset} a declared domain resolves and is then dropped: names work, nothing
     * connects, and the cause is invisible. A version number does not answer this - the option is
     * a build-time choice - so the binary is asked what it was compiled with.
     *
     * @return The probe.
     */
    private Probe nftSetSupport() {
        final String name = "dnsmasq nftset";
        final String install = "install a dnsmasq built with nftset support"
                + " (Fedora and Debian both ship one)";
        try {
            final CommandResult result = context.runner()
                    .run(org.fuin.sokar.core.process.Command.of(DnsmasqProbe.versionCommand()));
            if (!result.successful()) {
                return Probe.unknown(name, "dnsmasq did not run, so declared domains may not be"
                        + " reachable", install);
            }
            return DnsmasqProbe.supportsNftSet(result.standardOutput())
                    ? Probe.ok(name, "yes")
                    : Probe.missing(name,
                            "this dnsmasq cannot open the firewall for declared domains", install);
        } catch (RuntimeException ex) {
            return Probe.missing(name, "dnsmasq is not installed, so no declared domain resolves",
                    install);
        }
    }

    /**
     * Reports a binary a task needs and the version it answers with.
     * <p>
     * By name, because each of these fails somewhere else entirely: without {@code nft} a
     * container comes up with no ruleset, and without {@code git} the gate has no mirror to serve.
     *
     * @param name What to report it as.
     * @param program The binary.
     * @param consequence What breaks without it, for the line that says so.
     * @return The probe.
     */
    /**
     * Reports the transports this machine can carry a message with, and whether each one answers.
     * <p>
     * Installed rather than built in, so the honest answer on a machine with none is that messages
     * go nowhere - not an error, and not silence either. An adapter that is there and does not
     * answer {@code describe} is worse than one that is absent, because a peer configured against
     * it looks configured.
     *
     * @return The probe.
     */
    private Probe transports() {
        final String name = "transports";
        final java.util.Map<String, java.nio.file.Path> found =
                context.paths().messaging().transportDirectory().byName();
        if (found.isEmpty()) {
            return Probe.degraded(name, "none installed, so a message reaches no peer",
                    "install a transport package");
        }
        final java.util.List<String> answers = new java.util.ArrayList<>();
        final java.util.List<String> silent = new java.util.ArrayList<>();
        found.forEach((transport, executable) -> {
            try {
                final CommandResult result = context.runner()
                        .run(org.fuin.sokar.core.process.Command.of(executable.toString(),
                                "describe"));
                if (result.successful()) {
                    answers.add(transport);
                } else {
                    silent.add(transport);
                }
            } catch (final RuntimeException ex) {
                silent.add(transport);
            }
        });
        if (silent.isEmpty()) {
            return Probe.ok(name, String.join(", ", answers));
        }
        return Probe.degraded(name,
                String.join(", ", silent) + " did not answer 'describe'"
                        + (answers.isEmpty() ? "" : "; " + String.join(", ", answers) + " did"),
                "run the adapter by hand to see why");
    }

    /**
     * Reports whether a message filter is installed.
     * <p>
     * Its own line rather than a footnote to {@code transports}, because the two failures are not
     * the same: with no transport a message reaches no peer, and with no filter <strong>nothing is
     * sent at all</strong> - that is what fail closed means here. A machine with every transport in
     * the world and no filter is silent, and an operator reading a list of green lines should not
     * have to work that out.
     *
     * @return The probe.
     */
    private Probe messageFilter() {
        final String name = "message filter";
        final java.nio.file.Path filter = context.paths().messaging().messageFilter();
        if (filter == null) {
            return Probe.degraded(name,
                    "none installed, so nothing a task writes ever leaves this machine",
                    "install " + SokarPaths.MESSAGE_FILTER);
        }
        return Probe.ok(name, filter.toString());
    }

    /**
     * Reports whether this machine can tell configuration it may apply from configuration it may
     * not.
     * <p>
     * Its own line, because the failure is quiet: a machine with nothing pinned does not break, it
     * simply stops following its projects, and would otherwise look fine while drifting further
     * from what its repositories say. And the direction is unusual enough to deserve naming - every
     * other check here is about work leaving.
     *
     * @return The probe.
     */
    private boolean followsNothing() {
        try {
            return new FollowedProjects(context.paths().projects().followed()).all().isEmpty();
        } catch (final java.io.IOException ex) {
            return false;
        }
    }

    private Probe configurationAnchor() {
        final String name = "configuration key";
        final java.nio.file.Path signers = context.paths().projects().configurationSigners();
        if (!java.nio.file.Files.isRegularFile(signers) && followsNothing()) {
            // Nothing followed, nothing to verify: a machine that only works in 'default' needs no key, and a
            // newcomer read DEGRADED here before the guide had said a word about keys.
            return Probe.ok(name, "none needed yet - no project is followed; work in 'default' needs none");
        }
        if (!java.nio.file.Files.isRegularFile(signers)) {
            return Probe.degraded(name,
                    "none pinned, so no configuration from a project's repository is applied here",
                    "write the key its configuration is signed with to " + signers);
        }
        try {
            final int keys = org.fuin.sokar.app.AllowedSigners.read(signers).size();
            if (keys == 0) {
                return Probe.degraded(name, signers + " names no key",
                        "write the key its configuration is signed with there");
            }
            return Probe.ok(name, keys + " pinned in " + signers);
        } catch (final java.io.IOException ex) {
            // One unreadable line refuses the whole file, by the same rule the message keyring
            // follows: a keyring that is partly understood is worse than one that is refused.
            return Probe.degraded(name, signers + " cannot be read: " + ex.getMessage(),
                    "fix the line it names");
        }
    }

    /**
     * Reports how this account's followed projects are doing.
     * <p>
     * <strong>Two silences that look alike and are not.</strong> A machine whose vault is shut is
     * not following, and a machine whose repository is unreachable is not following - the first is
     * waiting for its own person and the second is a fault somewhere else. Reporting them as one
     * line would send an operator to look in the wrong place, and after a restart the first is the
     * ordinary state rather than a problem.
     *
     * @return The probe.
     */
    private Probe following() {
        final String name = "following";
        final java.util.List<FollowedProjects.Followed> followed;
        try {
            followed = new FollowedProjects(context.paths().projects().followed()).all();
        } catch (final java.io.IOException ex) {
            return Probe.degraded(name, "cannot be read: " + CliErrors.reason(ex),
                    "check that this account can read " + context.paths().projects().followed());
        }
        if (followed.isEmpty()) {
            // Not a fault. An account that follows nothing is configured by hand, which is what
            // every account did until this existed.
            return Probe.ok(name, "no project repository, so nothing is reconciled here");
        }
        final java.util.List<String> behind = new java.util.ArrayList<>();
        for (final FollowedProjects.Followed one : followed) {
            if (!"APPLIED".equals(one.outcome()) && !"UNCHANGED".equals(one.outcome())) {
                behind.add(one.name() + " ("
                        + (one.outcome().isEmpty() ? "never tried"
                                : one.outcome().toLowerCase(java.util.Locale.ROOT)) + ")");
            }
        }
        if (behind.isEmpty()) {
            // Named with the commit each is in force at, because that is what a task of that
            // project actually runs against - and "up to date" without it says nothing a person
            // could check against the repository.
            final String said = followed.stream()
                    .map(one -> one.name() + " at "
                            + (one.commit().isEmpty() ? "nothing"
                                    : one.commit().substring(0, Math.min(8, one.commit().length())))
                            + (one.unverified() ? " (unverified)" : ""))
                    .collect(java.util.stream.Collectors.joining(", "));
            if (followed.stream().anyMatch(FollowedProjects.Followed::unverified)) {
                // Not a fault - somebody asked for it - but not something to leave unsaid either:
                // for those projects, whoever may push decides what tasks here may reach.
                return Probe.degraded(name, said + " - an unverified project applies whatever its"
                        + " repository says, unchecked", "sokar project following");
            }
            return Probe.ok(name, said);
        }
        // Read from the outcome rather than worked out again here: a shut vault is its own outcome
        // now, and a second rule beside it is how two answers about one machine come to differ.
        final boolean shut = followed.stream()
                .anyMatch(one -> "VAULT_LOCKED".equals(one.outcome()));
        return Probe.degraded(name, String.join(", ", behind)
                + (shut ? " - this account's vault is shut, which is why a private repository"
                        + " cannot be fetched" : ""),
                shut ? "unlock the vault: sokar vault unlock" : "sokar projects following");
    }

    /**
     * Reports the projects on this machine and how many repositories each has.
     * <p>
     * <strong>The count is the point.</strong> A project is a unit of work over one or more
     * repositories, and every report that named a project without saying how many it has would
     * read as though a project were one - which is exactly the assumption this machine no longer
     * makes. A project whose file cannot be read says so rather than being counted as one.
     *
     * @return The probe.
     */
    private Probe projects() {
        final String name = "projects";
        final java.util.List<ProjectInventory.Summary> projects;
        try {
            projects = new ProjectInventory(context).projects();
        } catch (final RuntimeException ex) {
            return Probe.degraded(name, "cannot be listed: " + ex.getMessage(), "sokar projects");
        }
        // What nothing owns any more is not listed here as a project with an unreadable file: it has its own line.
        final java.util.Set<String> running = new java.util.HashSet<>();
        new TaskInventory(context).tasks().stream().filter(TaskInventory.Task::running)
                .map(TaskInventory.Task::project).filter(java.util.Objects::nonNull).forEach(running::add);
        final java.util.List<ProjectInventory.Summary> owned = projects.stream()
                .filter(project -> !Prune.orphaned(project, running)).toList();
        if (owned.isEmpty()) {
            return Probe.ok(name, "none yet - one appears the first time you follow one");
        }
        final java.util.List<String> said = new java.util.ArrayList<>();
        for (final ProjectInventory.Summary project : owned) {
            if (DefaultProject.is(project.name()) && project.repositories().isEmpty()) {
                said.add(project.name() + " (no repository yet)");
                continue;
            }
            said.add(project.name() + " " + (project.repositories().isEmpty()
                    // Not "1". An unreadable file is not a project with one repository, and
                    // saying so would be the very thing this line exists to stop.
                    ? "(file unreadable)"
                    : project.repositories().size() + " repositor"
                            + (project.repositories().size() == 1 ? "y" : "ies")));
        }
        return Probe.ok(name, String.join(", ", said));
    }

    private Probe leftovers() {
        final String name = "leftovers";
        final Prune.Result found;
        try {
            found = new Prune(context).run(false, false);
        } catch (final RuntimeException ex) {
            return Probe.unknown(name, "cannot be looked for: " + ex.getMessage(), "sokar prune");
        }
        if (!found.found()) {
            return Probe.ok(name, "none: everything here belongs to a project or a task");
        }
        final java.util.List<String> projects = java.util.stream.Stream.concat(found.removes().stream(),
                found.keeps().stream()).filter(item -> "PROJECT".equals(item.kind())).map(Prune.Item::name).toList();
        return Probe.degraded(name, (found.removes().size() + found.keeps().size()) + " left over from earlier"
                + (projects.isEmpty() ? "" : ", among them the projects " + String.join(", ", projects))
                + (found.keeps().isEmpty() ? "" : "; " + found.keeps().size() + " of them hold work"),
                "sokar prune shows them; --yes removes them");
    }

    private Probe binary(String name, String program, String consequence) {
        try {
            final CommandResult result = context.runner()
                    .run(org.fuin.sokar.core.process.Command.of(program, "--version"));
            if (!result.successful()) {
                return Probe.missing(name, program + " is installed but did not run: "
                        + result.standardError().strip(), "check the installation of " + program);
            }
            return Probe.ok(name, result.trimmedOutput().lines().findFirst().orElse("unknown")
                    .replace(program + " ", "").replace("version ", ""));
        } catch (RuntimeException ex) {
            return Probe.missing(name, "not installed, so " + consequence,
                    "install " + program);
        }
    }

    /**
     * Reports how podman connects a rootless container, which decides where the git gate can bind.
     * <p>
     * Only pasta can map the host's loopback into the container. Under anything else the gate
     * binds every interface and sits on the operator's network with nothing but its per-task token
     * in front of it - which works, and is worth knowing.
     *
     * @return The probe.
     */
    private Probe rootlessNetwork() {
        final String name = "rootless network";
        final java.util.Optional<String> backend = context.podman().rootlessNetworkCmd();
        if (backend.isEmpty()) {
            return Probe.unknown(name, "podman did not say which backend it uses",
                    "run 'podman info --format {{.Host.RootlessNetworkCmd}}' and check podman is"
                            + " working");
        }
        if (org.fuin.sokar.runtime.LoopbackMapping.PASTA.equals(backend.get())) {
            return Probe.ok(name, backend.get());
        }
        return Probe.degraded(name, backend.get() + " cannot map the host's loopback, so the git"
                + " gate binds every interface and is reachable from this machine's network",
                "install passt and let podman use pasta, or accept that the per-task token is what"
                        + " keeps the gate shut");
    }

    /**
     * Reports whether a passphrase can be cached for the session.
     *
     * @return The probe.
     */
    private static Probe keyring() {
        final String name = "keyring";
        return org.fuin.sokar.vault.KernelKeyring.available()
                ? Probe.ok(name, "available")
                : Probe.degraded(name, "libkeyutils is missing, so no passphrase can be cached and"
                        + " every vault command asks again", "install keyutils");
    }

    /**
     * Reports whether this account has a vault, as a state of its own.
     * <p>
     * A machine just prepared has none, and an interface asked to infer that from an empty credential list
     * cannot tell it from a vault that is shut or holds nothing yet. Degraded rather than missing: a task that
     * needs no credential runs without one.
     *
     * @return The probe.
     */
    private Probe vault() {
        final String name = "vault";
        return context.vault().exists()
                ? Probe.ok(name, "there")
                : Probe.degraded(name, "there is no vault here yet, so no credential can be stored and no device"
                        + " enrolled", "run 'sokar setup' at a terminal (over ssh: 'ssh -t'), which creates it");
    }

    /**
     * Reports whether podman will actually run Sokar's hooks.
     * <p>
     * The hooks are what load the firewall. Without them a container starts with no egress policy
     * at all and looks entirely normal, so this is the one line here that decides the exit code.
     *
     * @return A line describing the state.
     */
    private Probe hookRegistration() {
        final String name = "hooks registered";
        return switch (context.hooks().registration()) {
            case ACTIVE -> Probe.ok(name, "registered");
            case MISSING -> Probe.missing(name, "a task would run with no firewall at all",
                    "run 'sokar setup'");
            case DANGLING -> Probe.missing(name,
                    "the descriptors name hook binaries that are not installed",
                    "run 'sokar setup' again");
            case SHADOWED -> Probe.missing(name,
                    "another containers.conf.d drop-in sorts after Sokar's and points hooks_dir at "
                            + context.hooks().effectiveHooksDirectories(),
                    "remove that drop-in or make it sort before Sokar's");
            // What a package upgrade leaves behind: the binaries were replaced, these files were
            // not, and nothing runs 'sokar setup' for the operator.
            case STALE -> Probe.missing(name,
                    "the installed hook files are from a different version of Sokar: "
                            + context.hooks().outdated(),
                    "run 'sokar setup' again");
        };
    }

    /**
     * Reports whether a task container will be able to reach Sokar's sockets.
     * <p>
     * Without the policy the container's connection is refused and the agent reports an
     * authentication failure, so the cause is asked about here rather than left to be guessed.
     *
     * @return A line describing the state.
     */
    private Probe socketPolicy() {
        final String name = "selinux policy";
        if (!SocketContext.selinuxPresent()) {
            return Probe.ok(name, "not needed - this machine does not run SELinux");
        }
        if (SocketContext.available()) {
            return Probe.ok(name, "installed");
        }
        return Probe.missing(name, "a task cannot reach the vault proxy",
                context.paths().selinuxInstaller());
    }

    /**
     * Returns the directories agents are scanned in, in order.
     *
     * @return Locations.
     */
    private java.util.List<java.nio.file.Path> agentLocations() {
        return context.paths().agents().agentDirectory().locations();
    }

    /**
     * Reports binaries that are installed and never run, naming what hides each one.
     * <p>
     * Both halves are the finding. "This copy is unused" sends an operator looking for the one
     * that is used; found on a real machine as yesterday's build in the data directory, silently
     * winning over the packaged one. Nothing is printed when nothing is shadowed - a line that is
     * always there is a line nobody reads.
     *
     * @param out Where to write.
     */
    private void printShadowed(PrintWriter out) {

        final java.util.List<java.nio.file.Path[]> hidden = new java.util.ArrayList<>();
        final java.nio.file.Path hooks = context.paths().shadowedHookBinaries();
        if (hooks != null) {
            hidden.add(new java.nio.file.Path[] { hooks, context.paths().binaryDirectory() });
        }
        final org.fuin.sokar.agent.api.AgentDirectory agents = context.paths().agents().agentDirectory();
        final java.util.List<java.nio.file.Path> used = agents.executables();
        for (final java.nio.file.Path shadowed : agents.shadowed()) {
            used.stream()
                    .filter(winner -> winner.getFileName().equals(shadowed.getFileName()))
                    .findFirst()
                    .ifPresent(winner ->
                            hidden.add(new java.nio.file.Path[] { shadowed, winner }));
        }
        agents.hiddenDescriptions().forEach((loser, winner) ->
                hidden.add(new java.nio.file.Path[] { loser, winner }));
        final java.util.List<org.fuin.sokar.agent.api.AgentDirectory.Description> refused = agents.descriptions()
                .stream().filter(description -> description.refusal() != null).toList();
        if (hidden.isEmpty() && refused.isEmpty()) {
            return;
        }
        out.println();
        for (final java.nio.file.Path[] pair : hidden) {
            out.println(painted("not used", Probe.State.DEGRADED, colour) + " " + pair[0]);
            out.println("         hidden by " + pair[1]);
        }
        for (final org.fuin.sokar.agent.api.AgentDirectory.Description description : refused) {
            out.println(painted("not taken", Probe.State.DEGRADED, colour) + " " + description.file());
            out.println("         " + description.refusal());
        }
    }

    /**
     * Reports the container runtime's version, and whether Sokar will run on it.
     * <p>
     * podman 4 is refused: it has no pasta, so the host's loopback cannot be mapped into a
     * container and the git gate would bind every interface. Reported here as well as at task
     * start, because this is the command an operator runs to find out what their machine can do.
     *
     * @return The version, with what is wrong with it when something is.
     */
    private Probe podmanVersion() {
        final String name = "podman";
        final java.util.Optional<String> tooOld = context.podman().unsupportedVersion();
        return tooOld.isPresent()
                ? Probe.missing(name, tooOld.get(),
                        "run 'podman version' as this user and fix what it reports; Sokar needs podman 5"
                                + " or newer")
                : Probe.ok(name, context.podman().version());
    }

    /**
     * Every external thing a task depends on, in the order an operator would work through them.
     * <p>
     * Package-private so the list itself can be checked - that each failure carries an action, and
     * that nothing was dropped - without reading the printed page.
     *
     * @return The probes.
     */
    /**
     * Returns what this machine has of everything a task depends on.
     * <p>
     * Exists so the daemon can answer the same question without building a command-line object to
     * ask it. Both go through this, so a machine cannot be reported ready by one and unready by
     * the other - which is the drift that makes a diagnostic worth less than no diagnostic.
     *
     * @param context The machine to probe.
     * @return The probes, in the order they are reported.
     */
    public static java.util.List<Probe> probesFor(SokarContext context) {
        final DoctorCommand command = new DoctorCommand();
        command.setContext(context);
        return command.probes();
    }

    /**
     * Says whether this machine can run a task.
     * <p>
     * Anything missing means a task will fail, or run without something it needs and say nothing -
     * hooks that never load a firewall being the worst of them. Degraded and unknown leave a
     * machine ready: it does run tasks, and the report says how well.
     * <p>
     * One rule, used by the exit code and by the daemon's answer. Two summaries of one machine
     * that can disagree are worse than one, because whichever a person saw last is the one they
     * act on.
     *
     * @param probes What was found.
     * @return Whether work can start here.
     */
    public static boolean ready(java.util.List<Probe> probes) {
        return probes.stream().noneMatch(probe -> probe.state() == Probe.State.MISSING);
    }

    /**
     * Reports whether the daemon runs, and whether it is this command's version.
     * <p>
     * An update replaces the binaries and starts nothing, so a daemon that missed its own restart went on as the old
     * version with nothing saying so. Not running is not a failure: nothing on the command line needs it.
     *
     * @param socket Where the daemon listens.
     * @param own This command's version.
     * @param versionOf Asks the daemon at a socket for its version; throws when it cannot.
     * @return The probe.
     */
    static Probe daemon(java.nio.file.Path socket, String own,
            java.util.function.Function<java.nio.file.Path, String> versionOf) {
        final String name = "daemon";
        if (!java.nio.file.Files.exists(socket)) {
            return Probe.degraded(name, "not running - nothing on the command line needs it; an interface does",
                    "systemctl --user start sokard");
        }
        final String running;
        try {
            running = versionOf.apply(socket);
        } catch (RuntimeException ex) {
            return Probe.unknown(name, "does not answer at " + socket + ": " + ex.getMessage(),
                    "systemctl --user restart sokard");
        }
        if (!running.equals(own)) {
            return Probe.degraded(name, "runs " + running + ", this command is " + own
                    + " - an update was installed and the daemon was not restarted on it",
                    "systemctl --user daemon-reload && systemctl --user restart sokard");
        }
        return Probe.ok(name, "running, " + running);
    }

    /**
     * Asks the daemon at a socket for its version, within a few seconds, on a thread of its own.
     *
     * @param socket Where it listens.
     * @return Its version.
     */
    static String versionAt(java.nio.file.Path socket) {
        final java.util.concurrent.ExecutorService asking = java.util.concurrent.Executors.newSingleThreadExecutor(
                task -> {
                    final Thread thread = new Thread(task, "sokar-doctor-daemon");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            return asking.submit(() -> {
                try (org.fuin.sokar.wire.varlink.VarlinkClient client =
                        new org.fuin.sokar.wire.varlink.VarlinkClient(socket)) {
                    return String.valueOf(client.call("org.varlink.service.GetInfo", java.util.Map.of())
                            .get("version"));
                }
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IllegalStateException("no answer within 5 seconds", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            final Throwable cause = ex.getCause();
            throw new IllegalStateException(cause == null ? "failed" : String.valueOf(cause.getMessage()), ex);
        } finally {
            asking.shutdownNow();
        }
    }

    java.util.List<Probe> probes() {
        return java.util.List.of(
                podmanVersion(),
                hookRegistration(),
                rootlessNetwork(),
                nftSetSupport(),
                binary("nft", "nft", "a container comes up with no firewall ruleset"),
                binary("git", "git", "the gate has no mirror to serve and no push can be reviewed"),
                transports(), messageFilter(), configurationAnchor(), following(), projects(), leftovers(),
                binary("nsenter", "nsenter", "nothing can enter a container's network namespace,"
                        + " so a clearance decision cannot be applied to a running task"),
                keyring(), vault(),
                socketPolicy(),
                daemon(context.paths().daemonSocket(), SokarVersion.version(), DoctorCommand::versionAt));
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        // A terminal only: piped or written to a file, the report carries no escape codes.
        final java.io.Console console = System.console();
        colour = colours(color, console != null && console.isTerminal(), System.getenv("NO_COLOR"));
        final XdgPaths paths = context.paths().xdg();
        out.println("config   " + paths.config());
        out.println("data     " + paths.data());
        out.println("state    " + paths.state());
        // Only when there is something in it. A line naming an empty log on every healthy machine
        // teaches people to skip the line, and then it says nothing on the day it matters.
        if (java.nio.file.Files.isRegularFile(context.paths().failureLog())) {
            out.println(painted("failures", Probe.State.DEGRADED, colour) + " " + context.paths().failureLog()
                    + " - a command failed unexpectedly");
        }
        out.println("runtime  " + paths.runtime());

        out.println();
        out.println("hooks    " + context.paths().binaryDirectory());
        for (final java.nio.file.Path location : agentLocations()) {
            out.println("agents   " + location);
        }
        printShadowed(out);

        out.println();
        final java.util.List<Probe> probes = probes();
        for (final Probe probe : probes) {
            out.printf("%s %s%s%n", painted(String.format("%-19s", probe.name()), probe.state(), colour),
                    probe.healthy() ? "" : painted(probe.state().name(), probe.state(), colour) + " - ",
                    probe.detail());
            if (!probe.healthy()) {
                // Indented under the line it belongs to: an operator reading this is looking for
                // the one thing to do, and a list of findings without them is a list of worries.
                out.println("                    -> " + probe.action());
            }
        }

        out.println();
        out.println("dumpable            " + ProcessHardening.dumpable());
        out.println("no new privileges   " + ProcessHardening.noNewPrivileges());
        out.println("hardening covers    "
                + (ProcessHardening.appliesToWholeProcess() ? "the whole process" : "this thread only"));

        out.flush();

        // Anything missing means a task will fail, or run without something it needs and say
        // nothing - hooks that never load a firewall being the worst of them. Degraded and unknown
        // do not fail the command: the machine works, and the report says how well.
        if (!ready(probes)) {
            return 69;
        }

        // On a JVM this is a warning, in the shipped binary it must never appear.
        return ProcessHardening.appliesToWholeProcess() ? 0 : 1;
    }
}
