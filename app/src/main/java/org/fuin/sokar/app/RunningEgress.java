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

        /** What it would do, having changed nothing. */
        PREVIEWED,

        /** Every name was already granted to this run. */
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
