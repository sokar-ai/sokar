package org.fuin.sokar.app;

import java.io.PrintWriter;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.core.project.Project;
import org.jspecify.annotations.Nullable;

/**
 * What a task may reach, and where each destination came from.
 * <p>
 * Split out of {@code TaskRunCommand}, which had grown to hold six unrelated concerns. This one
 * answers a single question - which hosts are open to this task and who decided so - and it is
 * what an operator reads at the top of every run, so it is worth being able to find.
 * <p>
 * Every destination carries its origin because "what is open" and "who decided this" are
 * different questions, and only the second tells an operator what to change.
 */
final class EgressReport {

    /**
     * Hosts whose reachability changes what the git gate is worth, matched on the registrable
     * name so a subdomain counts too.
     */
    private static final java.util.List<String> FORGES = java.util.List.of("github.com",
            "gitlab.com", "bitbucket.org", "codeberg.org", "githubusercontent.com");

    private EgressReport() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the hosts the project declared, each mapped to where it came from.
     *
     * @param project The project.
     * @param sets Where the installed sets are found.
     * @return Host to origin, empty when the project declared nothing.
     */
    static java.util.Map<String, String> projectEgress(Project project,
            org.fuin.sokar.shield.EgressSetDirectory sets) {
        final org.fuin.sokar.core.project.Egress egress = project.egress();
        if (egress.isEmpty()) {
            return java.util.Map.of();
        }
        final java.util.Map<String, String> origins = new java.util.LinkedHashMap<>();
        sets.origins(egress.sets()).forEach((host, set) -> origins.put(host, "set " + set));
        // A directly named host wins the label: an operator who wrote it down should see it
        // reported as their own decision, not as whichever set happens to contain it too.
        egress.domains().forEach(domain -> origins.put(domain, "project"));
        return origins;
    }

    /**
     * Returns the destinations an agent asks for and is deliberately not given.
     *
     * @param selected The chosen agent, or {@code null}.
     * @return Hosts, empty when the agent names none.
     */
    static java.util.List<String> refused(@Nullable InstalledAgent selected) {
        return selected == null ? java.util.List.of() : selected.definition().refusedDomains();
    }

    /**
     * What may be reached, and who granted each host.
     *
     * @param domains Hosts the resolver will answer for, in the order the sources were consulted.
     * @param origins The same hosts, each mapped to the source that granted it.
     */
    record Reachable(java.util.List<String> domains, java.util.Map<String, String> origins) {
    }

    /**
     * Puts the three sources together, first grant wins.
     * <p>
     * One implementation because two callers need the same answer: the run that builds the
     * resolver configuration from it, and the editor that shows what a project may reach without
     * starting anything. Two would drift, and the one that drifted would be the one nobody was
     * watching.
     *
     * @param selected The chosen agent, or {@code null} when the task has none.
     * @param serving The provider serving it, or {@code null}.
     * @param projectOrigins What the project itself declared, from {@link #projectEgress}.
     * @return The hosts and their origins.
     */
    static Reachable compose(@Nullable InstalledAgent selected,
            @Nullable SelectedProvider serving,
            java.util.Map<String, String> projectOrigins) {

        final java.util.List<String> domains = new java.util.ArrayList<>(selected == null
                ? java.util.List.of() : selected.definition().allowedDomains());
        final java.util.Map<String, String> origins = new java.util.LinkedHashMap<>();
        if (selected != null) {
            selected.definition().allowedDomains().forEach(host ->
                    origins.putIfAbsent(host, "agent " + selected.definition().name()));
        }
        // The provider's own host, which the agent no longer restates. It has to be here or the
        // firewall denies it: measured, one agent contacts the provider before it starts and
        // quits when it cannot, whatever the credential is.
        if (serving != null) {
            for (final String host : serving.definition().domains()) {
                if (!domains.contains(host)) {
                    domains.add(host);
                }
                origins.putIfAbsent(host, "provider " + serving.definition().name());
            }
        }
        projectOrigins.forEach((host, origin) -> {
            if (!domains.contains(host)) {
                domains.add(host);
            }
            origins.putIfAbsent(host, origin);
        });
        // Unmodifiable, not copied into a hash map: this is read in the order the sources were
        // consulted - the agent's own hosts, then its provider's, then the project's - and a
        // report whose order changes between runs is one nobody can diff against yesterday's.
        return new Reachable(java.util.List.copyOf(domains),
                java.util.Collections.unmodifiableMap(origins));
    }

    /**
     * Returns the sentence that says what reaching a forge costs, or {@code null}.
     * <p>
     * Said wherever a forge becomes reachable - at the top of a run, and when an edit opens one -
     * because that is the moment the gate stops being a wall and becomes a convention.
     *
     * @param project The project.
     * @param origins Hosts and their origins.
     * @return The sentence, or {@code null} when no forge is open or the class does not care.
     */
    @Nullable
    static String forgeNote(Project project, java.util.Map<String, String> origins) {
        final java.util.List<String> forges = origins.keySet().stream()
                .filter(host -> FORGES.stream().anyMatch(forge ->
                        host.equals(forge) || host.endsWith("." + forge)))
                .toList();
        if (forges.isEmpty()
                || project.securityClass() != org.fuin.sokar.core.project.SecurityClass.GUARDED) {
            return null;
        }
        // Named, not listed: a whole set is eight hosts, and eight names in one sentence is a line
        // nobody reads - which would defeat the point of warning at all.
        final String what = forges.size() == 1 ? forges.get(0) + " is"
                : forges.get(0) + " and " + (forges.size() - 1) + " more forge host"
                        + (forges.size() == 2 ? "" : "s") + " are";
        return what + " reachable, so the gate now rests on this container holding no credential"
                + " for them";
    }

    /**
     * Prints every destination a task may reach, with who decided it.
     * <p>
     * One list rather than a line per source, because the question an operator has is "what can
     * this reach, and who said so" - and four differently shaped lines do not answer it. A
     * destination that was deliberately refused is listed too, and marked: "we said no" and
     * "nobody mentioned it" are different states, and only one of them is a thing to go and fix.
     *
     * @param project The project.
     * @param origins Host to the origin that granted it, in the order the sources were consulted.
     * @param refused Hosts an agent declares it asks for and is not given.
     * @param out Where to report.
     */
    static void reportReachable(Project project, java.util.Map<String, String> origins,
            java.util.List<String> refused, PrintWriter out) {

        if (origins.isEmpty() && refused.isEmpty()) {
            out.println("reachable      nothing - no agent, provider or project declared a host");
            out.flush();
            return;
        }

        final int width = java.util.stream.Stream.concat(origins.keySet().stream(),
                        refused.stream())
                .mapToInt(String::length).max().orElse(0);

        String label = "reachable";
        for (final java.util.Map.Entry<String, String> entry : origins.entrySet()) {
            out.printf("%-14s %-" + width + "s  %s%n", label, entry.getKey(), entry.getValue());
            label = "";
        }
        for (final String host : refused) {
            out.printf("%-14s %-" + width + "s  %s%n", label, host, "refused on purpose");
            label = "";
        }
        out.println("               ports 80 and 443; everything else is NXDOMAIN");

        // Said once, here, where the grants are. Not refused: an agent legitimately clones
        // dependencies from a forge.
        final String forges = forgeNote(project, origins);
        if (forges != null) {
            out.println("               " + forges);
        }
        out.flush();
    }
}
