package org.fuin.sokar.app;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.agent.api.InstalledAgents;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.shield.EgressSetDirectory;
import org.fuin.sokar.shield.EgressSetException;
import org.jspecify.annotations.Nullable;

/**
 * Reading and changing what a project may reach.
 * <p>
 * The CLI renders this and the daemon serializes it, so the most consequential edit in the product
 * is made in one place. Two implementations would differ exactly where it matters - which sets are
 * refused, whether a class forbids the change, what a change is reported to open - and the one
 * that drifted would be the one nobody was watching.
 */
public final class EgressControl {

    /** What to change about a project's declaration. Every list may be empty. */
    public record Change(List<String> addSets, List<String> removeSets, List<String> addDomains,
            List<String> removeDomains) {

        /**
         * Constructor with defensive copies.
         *
         * @param addSets Sets to declare.
         * @param removeSets Sets to stop declaring.
         * @param addDomains Hosts to declare directly.
         * @param removeDomains Hosts to stop declaring.
         */
        public Change {
            addSets = List.copyOf(addSets);
            removeSets = List.copyOf(removeSets);
            addDomains = List.copyOf(addDomains);
            removeDomains = List.copyOf(removeDomains);
        }

        /**
         * Tells whether this asks for anything.
         *
         * @return {@code true} when every list is empty.
         */
        public boolean isEmpty() {
            return addSets.isEmpty() && removeSets.isEmpty()
                    && addDomains.isEmpty() && removeDomains.isEmpty();
        }
    }

    /** What became of a change. */
    public enum Outcome {

        /** Applied and written. */
        CHANGED,

        /** What it would do, having written nothing. */
        PREVIEWED,

        /** The file already said that. */
        NO_CHANGE,

        /** A named set is not installed on this machine, so the file would name nothing real. */
        NO_SUCH_SET,

        /** The project's security class forbids declaring egress at all. */
        REFUSED_BY_CLASS,

        /** The project file could not be read. */
        UNREADABLE,

        /** The change was right and the file could not be written. */
        NOT_WRITTEN
    }

    /**
     * What a change does, in hosts rather than in set names.
     *
     * @param outcome What became of it.
     * @param opens Hosts it adds, each with the origin that grants it.
     * @param closes Hosts it takes away, each with the origin that granted it.
     * @param cost What reaching a forge costs a guarded project, when this change opens one.
     * @param detail Why it was refused, or {@code null}.
     */
    public record Effect(Outcome outcome, Map<String, String> opens, Map<String, String> closes,
            @Nullable String cost, @Nullable String detail) {

        /**
         * Constructor with defensive copies.
         *
         * @param outcome What became of it.
         * @param opens Hosts it adds.
         * @param closes Hosts it takes away.
         * @param cost The forge sentence, or {@code null}.
         * @param detail Why it was refused, or {@code null}.
         */
        public Effect {
            // Order is the report: hosts come out grouped by the set that granted them, which is
            // how an operator checks that a set is what they thought it was.
            opens = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(opens));
            closes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(closes));
        }

        static Effect refused(Outcome outcome, String detail) {
            return new Effect(outcome, Map.of(), Map.of(), null, detail);
        }
    }

    private final SokarContext context;

    /**
     * Constructor with the context to act through.
     *
     * @param context Where the paths and the installed sets come from.
     */
    public EgressControl(SokarContext context) {
        this.context = context;
    }

    /**
     * Returns what a project may reach, host to the origin that grants it.
     * <p>
     * The agent's own grants and its provider's host are included, through the same composition a
     * task run uses: what an operator wants to know is what the task will reach, and half of it is
     * not in the project file.
     *
     * @param projectFile The project file.
     * @param agentName Agent to include, or {@code null} for the only one installed.
     * @return Hosts and origins.
     * @throws ProjectException If the project file cannot be read.
     * @throws EgressSetException If it names a set this machine does not have.
     */
    public Map<String, String> reachable(Path projectFile, @Nullable String agentName) {
        return reachable(projectFile, null, agentName);
    }

    /**
     * Returns what a task on one repository may reach, host to the origin that grants it.
     * <p>
     * A repository's grants are <strong>added</strong> to the project's, so asking without one
     * answers the project-level set - true of every repository - and asking with one answers that
     * plus what it adds. Each host carries which of the two granted it.
     *
     * @param projectFile The project file.
     * @param repository Which repository, or {@code null} for the project's own.
     * @param agentName Agent to include, or {@code null} for the only one installed.
     * @return Hosts and origins.
     */
    public Map<String, String> reachable(Path projectFile, @Nullable String repository,
            @Nullable String agentName) {
        final Project project = ProjectReader.read(projectFile);
        final Map<String, String> declared = EgressReport.projectEgress(project,
                GateSupport.repository(project, repository), context.paths().egressSets());
        try (InstalledAgents agents = context.agents()) {
            final InstalledAgent selected = TaskLaunch.select(agents, agentName);
            final SelectedProvider serving = selected == null ? null
                    : SelectedProvider.choose(context.providers(), selected.definition(), null);
            return EgressReport.compose(selected, serving, declared).origins();
        }
    }

    /**
     * Returns the names a task would refuse whatever allows them, each with who refused it.
     *
     * @param projectFile The project file.
     * @param repository Which repository, or {@code null} for the project's own.
     * @param agentName Agent to include, or {@code null} for the only one installed.
     * @return Host to who refused it, empty when nobody refuses anything.
     */
    public Map<String, String> refusals(Path projectFile, @Nullable String repository, @Nullable String agentName) {
        final Project project = ProjectReader.read(projectFile);
        try (InstalledAgents agents = context.agents()) {
            return EgressReport.refusals(TaskLaunch.select(agents, agentName), project,
                    GateSupport.repository(project, repository));
        }
    }

    /**
     * Applies a change to a project's declaration, or reports what it would do.
     * <p>
     * Nothing is written until the result has been parsed by the reader that would have to read it
     * later, so an edit that produced something the project reader refuses fails here rather than
     * at the next task.
     *
     * @param projectFile The project file.
     * @param change What to change.
     * @param dryRun Whether to stop before writing.
     * @return What it does, or why it was refused.
     */
    public Effect apply(Path projectFile, Change change, boolean dryRun) {
        return apply(projectFile, null, change, dryRun);
    }

    /**
     * Applies a change to one repository's declaration, or reports what it would do.
     * <p>
     * <strong>Which block this writes into is the whole of the write-back.</strong> A connection
     * a task made is a fact about the repository that task works on, and remembering it in the
     * project's block would widen every other repository of that project for a reason none of them
     * can see. The project's own repository has no block of its own - its egress <em>is</em> the
     * project's - so {@code null} means that one.
     * <p>
     * <strong>It can only ever add.</strong> A repository's grants are added to the project's, so
     * creating a repository's block takes nothing away from it; that is why the additive rule was
     * chosen over replacement, and this is the method that would otherwise have removed a grant
     * while granting one.
     *
     * @param projectFile The project file.
     * @param repository Which repository's block to edit, or {@code null} for the project's own.
     * @param change What to change.
     * @param dryRun Whether to stop before writing.
     * @return What it does, or why it was refused.
     */
    public Effect apply(Path projectFile, @Nullable String repository, Change change,
            boolean dryRun) {

        final Project project;
        final String original;
        try {
            project = ProjectReader.read(projectFile);
            original = Files.readString(projectFile, StandardCharsets.UTF_8);
        } catch (ProjectException ex) {
            return Effect.refused(Outcome.UNREADABLE, CliErrors.reason(ex));
        } catch (IOException ex) {
            return Effect.refused(Outcome.UNREADABLE,
                    "cannot read " + projectFile + ": " + ex.getMessage());
        }

        // The project's own repository is named after the project and has no block of its own, so
        // it is the project's block - and a caller that passed the project's name means that.
        final String block = repository == null || repository.isBlank()
                || repository.equals(project.name()) ? null : repository;
        final org.fuin.sokar.core.project.Repository chosen;
        try {
            chosen = GateSupport.repository(project, block);
        } catch (ProjectException ex) {
            return Effect.refused(Outcome.UNREADABLE, CliErrors.reason(ex));
        }
        // What that block says today, which is what the change is applied to. The project's lists
        // for the project's block, the repository's own for a repository's - editing the project's
        // and writing the result into a repository would copy every project-level grant into it.
        final org.fuin.sokar.core.project.Egress declared =
                block == null ? project.egress() : chosen.egress();

        final EgressSetDirectory sets = context.paths().egressSets();
        final String updated;
        try {
            updated = EgressEdit.withEgress(original, block,
                    edited(declared.sets(), change.addSets(), change.removeSets()),
                    edited(declared.domains(), change.addDomains(), change.removeDomains()));
        } catch (IllegalArgumentException ex) {
            // A repository the file does not name. Refused rather than written to the project's
            // block, which is the one place this must not put it.
            return Effect.refused(Outcome.UNREADABLE, CliErrors.reason(ex));
        }

        final Project after;
        final Map<String, String> before;
        final Map<String, String> now;
        try {
            after = ProjectReader.read(new StringReader(updated), projectFile.toString());
            before = EgressReport.projectEgress(project, chosen, sets);
            now = EgressReport.projectEgress(after,
                    GateSupport.repository(after, block), sets);
        } catch (ProjectException ex) {
            // Where an offline project is refused, in the words that rule already chose.
            return Effect.refused(Outcome.REFUSED_BY_CLASS, CliErrors.reason(ex));
        } catch (EgressSetException ex) {
            // A set this machine does not have, whether the change added it or the file already
            // named one. Refused before anything is written: the file would otherwise name
            // something no task here could resolve, and every run would fail on it rather than
            // this one call. Checked by resolving rather than by a second look-up beside it -
            // deleting that look-up changed no behavior, which is how it was found to be
            // redundant.
            return Effect.refused(Outcome.NO_SUCH_SET, CliErrors.reason(ex));
        }

        final Map<String, String> opens = new LinkedHashMap<>(now);
        before.keySet().forEach(opens::remove);
        final Map<String, String> closes = new LinkedHashMap<>(before);
        now.keySet().forEach(closes::remove);

        // Said at the moment it becomes true, and not again on a later edit: a warning repeated
        // when nothing changed is one an operator learns to skip.
        final String cost = EgressReport.forgeNote(after, now) != null
                && EgressReport.forgeNote(project, before) == null
                        ? EgressReport.forgeNote(after, now) : null;

        if (opens.isEmpty() && closes.isEmpty()) {
            return new Effect(Outcome.NO_CHANGE, opens, closes, cost, null);
        }
        if (dryRun) {
            return new Effect(Outcome.PREVIEWED, opens, closes, cost, null);
        }
        try {
            Files.writeString(projectFile, updated, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return new Effect(Outcome.NOT_WRITTEN, opens, closes, cost,
                    "cannot write " + projectFile + ": " + ex.getMessage());
        }
        return new Effect(Outcome.CHANGED, opens, closes, cost, null);
    }

    /**
     * Returns the list with the additions appended and the removals gone.
     * <p>
     * Order is the file's own, and an addition goes at the end: rewriting the order of a list
     * somebody wrote would make the diff say more than the change did.
     */
    private static List<String> edited(List<String> current, List<String> additions,
            List<String> removals) {
        final List<String> wanted = new ArrayList<>(current);
        additions.forEach(value -> {
            if (!wanted.contains(value)) {
                wanted.add(value);
            }
        });
        wanted.removeAll(removals);
        return List.copyOf(wanted);
    }
}
