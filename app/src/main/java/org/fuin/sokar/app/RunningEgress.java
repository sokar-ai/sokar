package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.shield.DnsPolicy;
import org.fuin.sokar.wire.GrantedNames;
import org.jspecify.annotations.Nullable;

/**
 * Widening what a task that is already running may reach.
 * <p>
 * The alternative today is to stop the run and start another one, which throws away a workspace, a
 * gate token, commits that never reached the gate and an agent halfway through something - to add
 * one line to a file. This does it in place.
 * <p>
 * <strong>Two halves, because dnsmasq only gives one of them.</strong> A name has to resolve and
 * its addresses have to be let through. The resolver is told by appending to its servers file and
 * signaling it - the one part dnsmasq re-reads, measured - and the firewall is not told at all:
 * the name is recorded as granted to this run, the first connection is still dropped, and the
 * clearance watcher recognizes it and allows it without asking anybody. That costs one packet and
 * a retry, which is what every clearance decision costs.
 */
public final class RunningEgress {

    /** How far a change goes. Stated by the caller, never defaulted. */
    public enum Scope {

        /** This run only. The project file is untouched, and the next task will ask again. */
        RUN,

        /** This run, and the project file, so the next task starts with it. */
        RUN_AND_PROJECT
    }

    /** What became of a widening. */
    public enum Outcome {

        /** Applied to the running task. */
        WIDENED,

        /** Taken back from the running task. */
        NARROWED,

        /** What it would do, having changed nothing. */
        PREVIEWED,

        /** Every name was already granted to this run - or, when narrowing, none of them was. */
        NO_CHANGE,

        /** No such task, or it is not up. Nothing can be changed in a container that is gone. */
        NOT_RUNNING,

        /** The project is offline, so its tasks reach nothing and this is not a way around that. */
        REFUSED_BY_CLASS,

        /** The run was widened and the project file could not be, because nothing knows where it is. */
        NO_PROJECT_FILE,

        /** The run could not be widened. {@code detail} says why. */
        FAILED
    }

    /**
     * What a widening did.
     *
     * @param outcome What became of it.
     * @param opens Names it grants, in the order they were asked for.
     * @param persisted Whether the project file was changed as well.
     * @param detail Why it was refused or what went wrong, or {@code null}.
     */
    public record Effect(Outcome outcome, List<String> opens, boolean persisted,
            @Nullable String detail) {

        /**
         * Constructor with defensive copies.
         *
         * @param outcome What became of it.
         * @param opens Names it grants.
         * @param persisted Whether the project file was changed too.
         * @param detail Why, or {@code null}.
         */
        public Effect {
            opens = List.copyOf(opens);
        }

        static Effect refused(Outcome outcome, String detail) {
            return new Effect(outcome, List.of(), false, detail);
        }
    }

    private final SokarContext context;

    /**
     * Constructor with the context to act through.
     *
     * @param context Where the paths, the runtime and the project registry come from.
     */
    public RunningEgress(SokarContext context) {
        this.context = context;
    }

    /**
     * Grants names to a running task, and optionally to its project file as well.
     *
     * @param container Container name.
     * @param names Host names to grant.
     * @param scope How far the change goes.
     * @param dryRun Whether to stop before changing anything.
     * @return What it did, or why it did nothing.
     */
    public Effect widen(String container, List<String> names, Scope scope, boolean dryRun) {

        final TaskInventory.Task task = new TaskInventory(context).tasks().stream()
                .filter(candidate -> candidate.name().equals(container))
                .findFirst().orElse(null);
        if (task == null || !task.running()) {
            return Effect.refused(Outcome.NOT_RUNNING,
                    "no running task called " + container);
        }
        if (SecurityClass.OFFLINE.name().equalsIgnoreCase(String.valueOf(task.securityClass()))) {
            // The class is the project's promise that its tasks reach nothing. A run that could
            // step around it would make the promise worth nothing, and the way out already exists:
            // raise the class in the project file and start a task.
            return Effect.refused(Outcome.REFUSED_BY_CLASS,
                    "project '" + task.project() + "' is offline, so its tasks reach nothing."
                            + " Raise the security class in the project file and start a task.");
        }

        final Path state = context.paths().containerState(container);
        final List<String> wanted = new ArrayList<>();
        names.stream().map(String::strip).filter(name -> !name.isEmpty())
                .filter(name -> !GrantedNames.covers(state, name))
                .forEach(name -> {
                    if (!wanted.contains(name)) {
                        wanted.add(name);
                    }
                });
        if (wanted.isEmpty()) {
            return new Effect(Outcome.NO_CHANGE, List.of(), false, null);
        }
        if (dryRun) {
            return new Effect(Outcome.PREVIEWED, wanted, false, null);
        }

        try {
            resolve(state, wanted);
            for (final String name : wanted) {
                GrantedNames.add(state, name);
            }
        } catch (IOException ex) {
            return Effect.refused(Outcome.FAILED, "cannot widen " + container + ": "
                    + ex.getMessage());
        }

        if (scope == Scope.RUN) {
            return new Effect(Outcome.WIDENED, wanted, false, null);
        }
        return persist(task, wanted);
    }

    /**
     * What a withdrawal did.
     *
     * @param outcome What became of it.
     * @param closes Names it takes back, in the order they were asked for.
     * @param addresses How many addresses were removed from the firewall.
     * @param persisted Whether the project file was changed as well.
     * @param detail Why it was refused or what went wrong, or {@code null}.
     */
    public record Withdrawal(Outcome outcome, List<String> closes, int addresses,
            boolean persisted, @Nullable String detail) {

        /**
         * Constructor with defensive copies.
         *
         * @param outcome What became of it.
         * @param closes Names it takes back.
         * @param addresses Addresses removed.
         * @param persisted Whether the project file was changed too.
         * @param detail Why, or {@code null}.
         */
        public Withdrawal {
            closes = List.copyOf(closes);
        }

        static Withdrawal refused(Outcome outcome, String detail) {
            return new Withdrawal(outcome, List.of(), 0, false, detail);
        }
    }

    /**
     * Takes names back from a running task.
     * <p>
     * <strong>This stops new connections and not the ones already running.</strong> The name stops
     * resolving and its recorded addresses come out of the firewall, so nothing new can be reached
     * - but the ruleset accepts {@code ct state established,related} without consulting the set
     * again, so a transfer in progress runs to its end. Anything reporting this has to say so:
     * "narrowed" on its own would claim the host is unreachable, and it is not, yet.
     * <p>
     * The addresses come from what was recorded when each grant was applied, never from resolving
     * the name again here. B12 rejected re-resolving for a measured reason: a CDN, GeoDNS or plain
     * round-robin answers Sokar and the container differently, and the addresses that differ are
     * exactly the ones a withdrawal would leave open.
     *
     * @param container Container name.
     * @param names Names to take back.
     * @param scope This run, or this run and the project file.
     * @param dryRun Whether to report rather than apply.
     * @return What happened.
     */
    public Withdrawal narrow(String container, List<String> names, Scope scope, boolean dryRun) {

        final TaskInventory.Task task = new TaskInventory(context).tasks().stream()
                .filter(candidate -> candidate.name().equals(container))
                .findFirst().orElse(null);
        if (task == null || !task.running()) {
            return Withdrawal.refused(Outcome.NOT_RUNNING, "no running task called " + container);
        }

        // No class refusal here, and deliberately: an offline project has granted nothing, so
        // this answers NO_CHANGE by itself. Taking a grant away can never widen anything, which
        // is the only thing a class exists to prevent.
        final Path state = context.paths().containerState(container);
        final List<String> granted = GrantedNames.all(state);
        final List<String> wanted = new ArrayList<>();
        names.stream().map(String::strip).filter(name -> !name.isEmpty())
                .filter(granted::contains)
                .forEach(name -> {
                    if (!wanted.contains(name)) {
                        wanted.add(name);
                    }
                });
        if (wanted.isEmpty()) {
            return new Withdrawal(Outcome.NO_CHANGE, List.of(), 0, false, null);
        }

        final List<String> addresses = new ArrayList<>();
        for (final String name : wanted) {
            addresses.addAll(org.fuin.sokar.wire.GrantedAddresses.forName(state, name));
        }
        if (dryRun) {
            return new Withdrawal(Outcome.PREVIEWED, wanted, addresses.size(), false, null);
        }

        try {
            unresolve(state, wanted);
            for (final String name : wanted) {
                GrantedNames.remove(state, name);
            }
        } catch (IOException ex) {
            return Withdrawal.refused(Outcome.FAILED, "cannot narrow " + container + ": "
                    + ex.getMessage());
        }

        final java.util.Optional<Long> pid = context.podman().pidOf(container);
        if (pid.isPresent()) {
            final org.fuin.sokar.shield.EgressPolicy policy =
                    new org.fuin.sokar.shield.EgressPolicy(context.runner(), pid.get());
            for (final String address : addresses) {
                policy.withdraw(address);
            }
        }

        if (scope == Scope.RUN) {
            return new Withdrawal(Outcome.NARROWED, wanted, addresses.size(), false, null);
        }
        return unpersist(task, wanted, addresses.size());
    }

    /**
     * Takes the names out of the running task's resolver, without restarting it.
     * <p>
     * The whole servers file is rewritten and the resolver signalled. Appending cannot remove a
     * line, and a line still in the file is a name dnsmasq still answers.
     *
     * @param state The task's state directory.
     * @param names Names to remove.
     * @throws IOException If the file cannot be written.
     */
    private void unresolve(Path state, List<String> names) throws IOException {

        final Path servers = state.resolve(DnsPolicy.SERVERS_FILE);
        if (!Files.isRegularFile(servers)) {
            return;
        }
        final List<String> kept = new ArrayList<>();
        for (final String line : Files.readAllLines(servers, StandardCharsets.UTF_8)) {
            final boolean withdrawn = names.stream()
                    .anyMatch(name -> line.strip().startsWith("server=/" + name + "/"));
            if (!withdrawn && !line.isBlank()) {
                kept.add(line.strip());
            }
        }
        Files.writeString(servers, kept.isEmpty() ? ""
                : String.join(System.lineSeparator(), kept) + System.lineSeparator(),
                StandardCharsets.UTF_8);

        final Path pidFile = state.resolve("dnsmasq.pid");
        if (!Files.isRegularFile(pidFile)) {
            throw new IOException("the resolver's pid file is missing, so it cannot be told");
        }
        context.runner().runOrFail(org.fuin.sokar.core.process.Command.of(
                "kill", "-HUP", Files.readString(pidFile, StandardCharsets.UTF_8).strip()));
    }

    /**
     * Tells the running task's resolver about the names, without restarting it.
     * <p>
     * Appended to the servers file and signaled: that file is the only part dnsmasq re-reads on
     * {@code SIGHUP}, so nothing is restarted and there is no window in which the container
     * resolves nothing. The recorded pid is the resolver's own - {@code nsenter} execs dnsmasq
     * rather than forking it, which is also why the poststop hook can reap it by that pid.
     *
     * @param state The task's state directory.
     * @param names Names to add.
     * @throws IOException If the file cannot be written.
     */
    private void resolve(Path state, List<String> names) throws IOException {

        final Path servers = state.resolve(DnsPolicy.SERVERS_FILE);
        final StringBuilder added = new StringBuilder();
        for (final String name : names) {
            for (final String resolver : upstreams(servers)) {
                added.append("server=/").append(name).append('/').append(resolver)
                        .append(System.lineSeparator());
            }
        }
        Files.writeString(servers, added.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        final Path pidFile = state.resolve("dnsmasq.pid");
        if (!Files.isRegularFile(pidFile)) {
            throw new IOException("the resolver's pid file is missing, so it cannot be told");
        }
        context.runner().runOrFail(org.fuin.sokar.core.process.Command.of(
                "kill", "-HUP", Files.readString(pidFile, StandardCharsets.UTF_8).strip()));
    }

    /**
     * Returns the resolvers this task's own configuration already forwards to.
     * <p>
     * Read back from the file rather than worked out again: a task keeps the upstreams it was
     * started with, and the host's may have changed since. Falls back to the host's for a task
     * whose project declared nothing, where there is no line to read.
     *
     * @param servers The task's servers file.
     * @return Resolver addresses.
     */
    private static Set<String> upstreams(Path servers) {
        final Set<String> found = new LinkedHashSet<>();
        try {
            for (final String line : Files.readAllLines(servers, StandardCharsets.UTF_8)) {
                final String[] parts = line.strip().split("/");
                if (line.startsWith("server=/") && parts.length == 3 && !parts[2].isBlank()) {
                    found.add(parts[2].strip());
                }
            }
        } catch (IOException ex) {
            // Falls through to the host's, below.
        }
        return found.isEmpty() ? new LinkedHashSet<>(TaskRunner.hostResolvers()) : found;
    }

    /**
     * Writes the same names into the project file, so the next task starts with them.
     *
     * @param task The running task.
     * @param names Names that were granted.
     * @return What became of it, the run having been widened either way.
     */
    /**
     * Takes the names out of the project file as well, so the next task does not start with them.
     *
     * @param task The running task.
     * @param names Names withdrawn from the run.
     * @param addresses How many addresses came out of the firewall.
     * @return What happened.
     */
    private Withdrawal unpersist(TaskInventory.Task task, List<String> names, int addresses) {

        final String project = task.project();
        final String file = project == null ? null
                : new ProjectInventory(context).projects().stream()
                        .filter(candidate -> candidate.name().equals(project))
                        .map(ProjectInventory.Summary::file)
                        .filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null);
        if (file == null) {
            // The run IS narrowed and says so. Calling this a failure would be the lie in the
            // dangerous direction here too, just pointing the other way: somebody would think the
            // host was still open to this task when it is not.
            return new Withdrawal(Outcome.NO_PROJECT_FILE, names, addresses, false,
                    "the run was narrowed; nothing knows where project '" + project
                            + "' keeps its file, so it was not written down");
        }
        final EgressControl.Effect written = new EgressControl(context).apply(Path.of(file),
                new EgressControl.Change(List.of(), List.of(), List.of(), names), false);
        if (written.outcome() != EgressControl.Outcome.CHANGED
                && written.outcome() != EgressControl.Outcome.NO_CHANGE) {
            return new Withdrawal(Outcome.NO_PROJECT_FILE, names, addresses, false,
                    "the run was narrowed; the project file was not: " + written.detail());
        }
        return new Withdrawal(Outcome.NARROWED, names, addresses, true, null);
    }

    private Effect persist(TaskInventory.Task task, List<String> names) {

        final String project = task.project();
        final String file = project == null ? null
                : new ProjectInventory(context).projects().stream()
                        .filter(candidate -> candidate.name().equals(project))
                        .map(ProjectInventory.Summary::file)
                        .filter(java.util.Objects::nonNull)
                        .findFirst().orElse(null);
        if (file == null) {
            // The run is widened and says so. Reporting this as a failure would be a lie in the
            // more dangerous direction: somebody would think nothing had changed.
            return new Effect(Outcome.NO_PROJECT_FILE, names, false,
                    "the run was widened; nothing knows where project '" + project
                            + "' keeps its file, so it was not written down");
        }
        final EgressControl.Effect written = new EgressControl(context).apply(Path.of(file),
                new EgressControl.Change(List.of(), List.of(), names, List.of()), false);
        if (written.outcome() != EgressControl.Outcome.CHANGED
                && written.outcome() != EgressControl.Outcome.NO_CHANGE) {
            return new Effect(Outcome.NO_PROJECT_FILE, names, false,
                    "the run was widened; the project file was not: " + written.detail());
        }
        return new Effect(Outcome.WIDENED, names, true, null);
    }
}
