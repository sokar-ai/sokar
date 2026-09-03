package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.agent.api.AgentDirectory;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.agent.api.InstalledAgents;
import org.fuin.sokar.core.config.XdgPaths;
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
    private Path directory;

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
                        AgentDirectory.standard(xdg.data()).locations().stream()).toList());

        try (InstalledAgents agents = new InstalledAgents(locations, xdg.runtime())) {

            if (agents.size() == 0 && agents.failures().isEmpty()) {
                out.println("no agents installed. Looked in:");
                locations.locations().forEach(path -> out.println("  " + path));
                out.flush();
                return 0;
            }

            if (agents.size() > 0) {
                out.printf("%-12s %-16s %-22s %s%n", "NAME", "BINARY", "LABEL", "FROM");
                for (final InstalledAgent agent : agents.all()) {
                    out.printf("%-12s %-16s %-22s %s%n", agent.name(),
                            agent.definition().binary(), agent.definition().label(),
                            agent.executable());
                    if (verbose) {
                        out.println("             domains: "
                                + String.join(", ", agent.definition().allowedDomains()));
                        if (agent.definition().route() != null) {
                            // Withheld from the firewall whenever a token is brokered, so an
                            // operator seeing it dropped knows it was meant to be.
                            out.println("             proxied: "
                                    + agent.definition().route().upstreamHost()
                                    + " (reachable only through the credential proxy)");
                        }
                        if (!agent.definition().refusedDomains().isEmpty()) {
                            // Shown so that a blocked connection is something the operator was
                            // told about, rather than something they find in a drop log.
                            out.println("             refused: "
                                    + String.join(", ", agent.definition().refusedDomains()));
                        }
                        out.println("             resume:  "
                                + (agent.definition().supportsResume() ? "yes" : "no"));
                    }
                    if (supplyChain) {
                        supplyChain(out, agent);
                    }
                }
                out.flush();
            }

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
