package org.fuin.sokar.app;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;

/**
 * Checks a draft {@code project.yml} against this machine, before a person commits it.
 * <p>
 * <strong>Three answers, because they block different things</strong> (decided on
 * 2026-09-30): what Sokar refuses anywhere - YAML that does not parse, a key or value the schema refuses - blocks the
 * commit; what this machine's {@code follow} would refuse - an egress set it does not have - means this machine
 * would not take it; and what only this machine lacks - a destination, a vault entry, a transport - or a key a
 * later Sokar may know, warns: one project may run on machines set up differently.
 * <p>
 * A transport's section under {@code mail.transports} is asked of the transport, when it offers a {@code settings}
 * check: what it would refuse anywhere blocks the commit, and what it warns of warns.
 */
public final class ProjectFileCheck {

    /**
     * What a draft is, on this machine.
     *
     * @param refused What Sokar refuses anywhere; blocks the commit.
     * @param refusedHere What this machine's follow would refuse.
     * @param warnings What only this machine lacks, or does not know.
     */
    public record Result(List<String> refused, List<String> refusedHere, List<String> warnings) {

        /**
         * Returns it as plain values, for a wire.
         *
         * @return The answer.
         */
        public Map<String, Object> asMap() {
            return Map.of("refused", refused, "refusedHere", refusedHere, "warnings", warnings);
        }
    }

    private ProjectFileCheck() {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> settings(Object section) {
        return section instanceof Map<?, ?> said ? (Map<String, Object>) said : Map.of();
    }

    /**
     * Checks a draft.
     *
     * @param context This machine.
     * @param text The draft's text.
     * @return What it is here.
     */
    public static Result check(SokarContext context, String text) {
        final List<String> refused = new ArrayList<>();
        final List<String> refusedHere = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Project project;
        try {
            project = ProjectReader.read(new StringReader(text), "project.yml");
        } catch (ProjectException ex) {
            refused.add(String.valueOf(ex.getMessage()));
            return new Result(refused, refusedHere, warnings);
        }
        if (DefaultProject.is(project.name())) {
            // Refused anywhere: no machine follows a project of that name.
            refused.add(FollowedProjects.RESERVED);
        }
        try {
            for (final String unknown : ProjectReader.unknownKeys(new StringReader(text), "project.yml")) {
                warnings.add("'" + unknown + "' is not a setting this machine's Sokar knows; it has no effect here");
            }
        } catch (RuntimeException ex) {
            // Read above already; nothing more to say about it.
        }
        for (final org.fuin.sokar.core.project.Repository repository : project.allRepositories()) {
            try {
                context.paths().egress().egressSets().origins(project.egressFor(repository).sets());
            } catch (org.fuin.sokar.shield.EgressSetException ex) {
                refusedHere.add("an egress set this machine does not have: " + ex.getMessage());
            }
        }
        final Map<String, Destination> destinations = Destination.all(context.paths().xdg().data());
        final var providers = context.providers();
        final Map<String, org.fuin.sokar.vault.VaultEntry> stored = context.readableCredentials().orElse(null);
        project.credentials().forEach((entry, destination) -> {
            if (Destination.resolve(destination, destinations, providers, null) == null) {
                warnings.add("credential '" + entry + "' is for '" + destination + "', and no destination or provider"
                        + " of that name is declared on this machine");
            }
            if (stored != null && !stored.containsKey(entry)) {
                warnings.add("credential '" + entry + "' is not in this machine's vault");
            }
        });
        for (final String scheme : project.mail().conversations()) {
            if (context.paths().messaging().transportDirectory().find(scheme) == null) {
                warnings.add("no '" + scheme + "' transport is installed on this machine, so its tasks cannot message");
            }
        }
        // A transport's own section is the transport's to judge: Sokar's schema leaves it open, and the
        // transport otherwise refuses it only when setup runs, at the first task start - after the commit.
        final TransportLifecycle transports = new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        project.mail().transports().forEach((scheme, section) -> {
            final String where = "mail.transports." + scheme + ": ";
            try {
                final TransportLifecycle.SettingsCheck said = transports.settings(scheme, settings(section));
                if (said != null) {
                    said.refused().forEach(each -> refused.add(where + each));
                    said.warnings().forEach(each -> warnings.add(where + each));
                }
            } catch (TransportLifecycle.Refused ex) {
                warnings.add(where + "not checked: " + ex.getMessage());
            }
        });
        return new Result(refused, refusedHere, warnings);
    }
}
