package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.agent.api.InstalledAgents;
import org.fuin.sokar.core.config.XdgPaths;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Lists the agents installed on this machine.
 * <p>
 * Note what this class does not contain: the name of a single agent. It scans a directory, starts
 * whatever it finds and asks each one to describe itself. An agent installed after this binary was
 * built appears here without this file changing.
 */
@Command(name = "agents",
        mixinStandardHelpOptions = true,
        description = "Lists the agents installed on this machine.")
public class AgentsCommand implements Callable<Integer> {

    @Option(names = "--verbose", description = "Also show what each agent needs to reach.")
    private boolean verbose;

    @Option(names = "--directory", paramLabel = "<path>",
            description = "Extra directory to scan, before the standard ones.")
    private @Nullable Path directory;

    @Option(names = "--supply-chain",
            description = "Show what each agent installs, and whether it can be verified.")
    private boolean supplyChain;

    @Spec
    private CommandSpec spec;

    private void supplyChain(PrintWriter out, InstalledAgent agent) {

        final var definition = agent.definition();
        out.println("             installs: "
                + (definition.version() == null ? "nothing" : definition.version()));

        for (final var artifact : definition.artifacts()) {
            out.println("               " + artifact.target());
            out.println("                 from   " + artifact.url());
            if (artifact.unverified()) {
                // Named rather than merely omitted: an operator auditing this needs the gaps to
                // stand out, not to be inferred from a missing line.
                out.println("                 sha256 UNVERIFIED - " + artifact.reason());
            } else {
                out.println("                 sha256 " + artifact.sha256());
            }
        }
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        final XdgPaths xdg = XdgPaths.current();
        final AgentDirectory locations = directory == null
                ? AgentDirectory.standard(xdg.data())
                : new AgentDirectory(java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(directory),
                        AgentDirectory.standard(xdg.data()).locations().stream()).toList(),
                        AgentDirectory.standard(xdg.data()).descriptionLocations());

        final java.util.Map<String, org.fuin.sokar.agent.api.ProviderDefinition> providers =
                org.fuin.sokar.agent.api.ProviderDirectory.standard(xdg.data()).all();

        try (InstalledAgents agents = new InstalledAgents(locations, xdg.runtime())) {

            if (agents.size() == 0 && agents.failures().isEmpty()) {
                out.println("no agents installed. Looked in:");
                locations.locations().forEach(path -> out.println("  " + path));
                out.flush();
                return 0;
            }

            // A vendor's description that names nothing usable is said, not quietly skipped: the agent it meant is
            // missing from the list, and the file is the one place that says why.
            for (final AgentDirectory.Description description : locations.descriptions()) {
                if (description.refusal() != null) {
                    err.println("sokar: not taken: " + description.file() + " - " + description.refusal());
                }
            }
            err.flush();
            if (agents.size() > 0) {
                out.printf("%-12s %-16s %-22s %s%n", "NAME", "BINARY", "LABEL", "FROM");
                for (final InstalledAgent agent : agents.all()) {
                    out.printf("%-12s %-16s %-22s %s%n", agent.name(),
                            agent.definition().binary(), agent.definition().label(),
                            agent.executable());
                    if (verbose) {
                        for (final AgentDirectory.Description description : locations.descriptions()) {
                            if (agent.executable().equals(description.executable())) {
                                out.println("             found by: " + description.file());
                            }
                        }
                        // The address the guard recognises. Somebody looking at a commit and
                        // wondering whether a person or an agent wrote it has nowhere else to
                        // find out, now that a pre-push hook decides on exactly this.
                        out.println("             commits: "
                                + agent.definition().gitIdentity().name() + " <"
                                + agent.definition().gitIdentity().email() + ">");
                        out.println("             domains: "
                                + String.join(", ", agent.definition().allowedDomains()));
                        if (agent.definition().provider() != null) {
                            out.println("             speaks:  "
                                    + agent.definition().provider().dialect());
                            for (final var entry : providers.entrySet()) {
                                if (agent.definition().provider().canDrive(entry.getValue())) {
                                    out.println("             provider:"
                                            + (entry.getKey().equals(
                                                agent.definition().provider().defaultProvider())
                                                    ? " " + entry.getKey() + " (default)"
                                                    : " " + entry.getKey())
                                            + " -> " + entry.getValue().upstreamHost()
                                            + " (the credential is swapped in on the way out)");
                                }
                            }
                        }
                        if (!agent.definition().refusedDomains().isEmpty()) {
                            // Shown so that a blocked connection is something the operator was
                            // told about, rather than something they find in a drop log.
                            out.println("             refused: "
                                    + String.join(", ", agent.definition().refusedDomains()));
                        }
                        out.println("             resume:  "
                                + (agent.definition().supportsResume() ? "yes" : "no"));
                        // What a check waits for once the agent is attached, and for how long. Said
                        // when it is absent too: then whether it reached work cannot be told.
                        final org.fuin.sokar.agent.api.ReadyMarker ready = agent.definition().ready();
                        out.println("             ready:   " + (ready == null
                                ? "not declared - whether it reached work cannot be told"
                                : "\"" + ready.text() + "\"" + (ready.withinSeconds() == null ? ""
                                        : " within " + ready.withinSeconds() + "s")));
                        // What waiting for a person looks like, counted rather than quoted: the wording is
                        // the agent's, and a count says enough to see whether there is any.
                        final org.fuin.sokar.agent.api.Waiting waiting = agent.definition().waiting();
                        out.println("             waiting: " + (waiting == null
                                ? "not declared - whether it waits for a person cannot be told"
                                : waiting.screen().size() + " screen rule(s)"
                                        + (waiting.lastMessage() == null ? "" : ", and where a run's last message is")));
                    }
                    if (supplyChain) {
                        supplyChain(out, agent);
                    }
                }
                out.flush();
            }

            // On standard output and not an error: nothing here is broken. A second binary
            // claiming a name that is already taken means the agent runs and somebody cannot see
            // which copy they are getting - worth saying, and not worth a non-zero exit on a
            // machine where everything works.
            agents.ignored().forEach((hidden, running) -> {
                out.println("ignored   " + hidden);
                out.println("          " + running + " claimed the same name first and runs");
            });
            out.flush();

            // Reported rather than thrown: one broken package must not make the machine look as
            // though it has no agents.
            for (final Map.Entry<String, String> failure : agents.failures().entrySet()) {
                err.println("sokar: " + failure.getKey() + " is installed but unusable: "
                        + failure.getValue());
            }
            err.flush();
            return agents.failures().isEmpty() ? 0 : 1;
        }
    }
}
