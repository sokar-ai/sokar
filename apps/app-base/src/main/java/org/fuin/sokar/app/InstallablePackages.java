package org.fuin.sokar.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;

/**
 * What this machine could install, asked of the machine's own package source.
 * <p>
 * <strong>Nobody maintains a list.</strong> Every published agent package declares {@code Provides:
 * sokar-agent} and every transport {@code Provides: sokar-transport}, so the repository is the
 * catalogue: a new agent appears here without a release of Sokar, of the interface, or of anything
 * else. A hand-kept list would be wrong the first time somebody published a package on a Friday.
 * The stub agent built in this repository deliberately does not: it is there for the acceptance
 * suite, and providing the name would offer a test agent to a person choosing one.
 * <p>
 * <strong>Real packages, never the virtual name.</strong> A virtual package with several providers
 * cannot be installed by its own name - the package manager asks or refuses - so what is listed is
 * always something a person can actually choose.
 * <p>
 * <strong>The filter is not in here, deliberately.</strong> It is not one of a kind to choose
 * from: it is the thing without which nothing leaves. Offering a choice would suggest a second
 * filter could take its place, and the product does not allow that.
 */
public final class InstallablePackages {

    /** The virtual package every published agent package declares it provides; the stub does not. */
    public static final String PROVIDES_AGENT = "sokar-agent";

    /** The virtual package every transport package declares it provides. */
    public static final String PROVIDES_TRANSPORT = "sokar-transport";

    /**
     * The virtual package a homeserver package declares it provides: what a transport's conversation runs on
     * when the project names none of its own. Offered beside the transports, so that choosing Matrix on a new
     * machine is not a first task start that fails for want of one.
     */
    public static final String PROVIDES_HOMESERVER = "sokar-homeserver";

    /**
     * What an agent is called in an answer.
     * <p>
     * Not the virtual package name: that is the mechanism by which this is found out, and a person
     * choosing between things does not need to know how the question was asked.
     */
    public static final String AGENT = "agent";

    /** What a transport is called in an answer. */
    public static final String TRANSPORT = "transport";

    /** What a homeserver is called in an answer. */
    public static final String HOMESERVER = "homeserver";

    /**
     * One thing this machine could install, or already has.
     *
     * @param name The real package name, which is what may be installed.
     * @param kind {@link #AGENT}, {@link #TRANSPORT} or {@link #HOMESERVER}.
     * @param description One line, from the package itself.
     * @param installed Whether it is on this machine now.
     * @param version What is installed, or what the repository offers. "" when neither is known.
     */
    public record Installable(String name, String kind, String description, boolean installed,
            String version) {
    }

    private final CommandRunner runner;

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     */
    public InstallablePackages(final CommandRunner runner) {
        this.runner = runner;
    }

    /**
     * Lists what this machine could install.
     *
     * @return Agents first, then transports, each sorted by name. Empty when this machine has no
     *         package manager either of us knows - which is a machine somebody built by hand, and
     *         not an error worth throwing about.
     */
    public List<Installable> list() {
        final Map<String, Installable> found = new LinkedHashMap<>();
        // Agents first, then transports, and in that order on purpose: it is the order a person
        // is asked to choose in, and Map.of would have decided it by hash.
        for (final List<String> kind : List.of(List.of(PROVIDES_AGENT, AGENT),
                List.of(PROVIDES_TRANSPORT, TRANSPORT), List.of(PROVIDES_HOMESERVER, HOMESERVER))) {
            for (final String name : providers(kind.get(0))) {
                // First kind wins: a package claiming both would otherwise appear twice and a
                // person would be offered the same install under two headings.
                found.putIfAbsent(name, describe(name, kind.get(1)));
            }
        }
        return List.copyOf(found.values());
    }

    private List<String> providers(final String virtualName) {
        if (has("apt-cache")) {
            // The index is as fresh as the last 'apt update'; refreshing is the caller's business,
            // because it needs root and this does not.
            final List<String> names = new ArrayList<>();
            boolean inReverseProvides = false;
            for (final String line : lines("apt-cache", "showpkg", virtualName)) {
                if (line.startsWith("Reverse Provides:")) {
                    inReverseProvides = true;
                } else if (inReverseProvides && !line.isBlank()) {
                    final String name = line.strip().split("\\s+")[0];
                    if (!name.isBlank() && !names.contains(name)) {
                        names.add(name);
                    }
                }
            }
            java.util.Collections.sort(names);
            return names;
        }
        if (has("dnf")) {
            final List<String> names = new ArrayList<>(lines("dnf", "repoquery", "--qf", "%{name}",
                    "--whatprovides", virtualName).stream().map(String::strip)
                    .filter(line -> !line.isBlank()).distinct().toList());
            java.util.Collections.sort(names);
            return names;
        }
        return List.of();
    }

    private Installable describe(final String name, final String kind) {
        String description = "";
        String version = "";
        boolean installed = false;
        if (has("dpkg-query")) {
            final CommandResult status = run("dpkg-query", "-W", "-f=${Version}", name);
            installed = status.exitCode() == 0 && !status.standardOutput().isBlank();
            version = status.standardOutput().strip();
            for (final String line : lines("apt-cache", "show", name)) {
                if (line.startsWith("Description:") && description.isEmpty()) {
                    description = line.substring("Description:".length()).strip();
                }
                if (line.startsWith("Version:") && version.isBlank()) {
                    version = line.substring("Version:".length()).strip();
                }
            }
        } else if (has("rpm")) {
            installed = run("rpm", "-q", name).exitCode() == 0;
            for (final String line : lines("dnf", "repoquery", "--qf",
                    "%{version}-%{release}|%{summary}", name)) {
                final int bar = line.indexOf('|');
                if (bar > 0) {
                    version = version.isBlank() ? line.substring(0, bar).strip() : version;
                    description = description.isEmpty() ? line.substring(bar + 1).strip()
                            : description;
                }
            }
        }
        return new Installable(name, kind, description, installed, version);
    }

    private boolean has(final String program) {
        return run("sh", "-c", "command -v " + program + " >/dev/null 2>&1").exitCode() == 0;
    }

    private List<String> lines(final String... command) {
        return List.of(run(command).standardOutput().split("\n", -1));
    }

    private CommandResult run(final String... command) {
        try {
            return runner.run(Command.of(command));
        } catch (final RuntimeException ex) {
            // A package manager that is not there, or refuses: an empty answer, because "this
            // machine offers nothing" is a true thing to say and an exception here would take the
            // whole daemon method down with it.
            return new CommandResult(Command.of(command), 1, "", String.valueOf(ex.getMessage()));
        }
    }
}
