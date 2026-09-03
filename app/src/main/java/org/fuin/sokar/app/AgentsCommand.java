package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.fuin.sokar.agent.api.Agent;
import org.fuin.sokar.agent.api.AgentRegistry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Lists the agents this binary was built with.
 * <p>
 * Note what this class does not contain: the name of a single agent. It asks the registry, which
 * asks {@link java.util.ServiceLoader}, which reports whatever modules were on the classpath at
 * build time. Adding an agent changes its output without changing its source.
 */
@Command(name = "agents",
        mixinStandardHelpOptions = true,
        description = "Lists the agents this build includes.")
public class AgentsCommand implements Callable<Integer> {

    @Option(names = "--verbose", description = "Also show what each agent needs to reach.")
    private boolean verbose;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final AgentRegistry registry = AgentRegistry.discover();

        if (registry.size() == 0) {
            out.println("this build includes no agents");
            out.flush();
            return 0;
        }

        out.printf("%-12s %-16s %s%n", "NAME", "BINARY", "LABEL");
        for (final Agent agent : registry.all()) {
            out.printf("%-12s %-16s %s%n", agent.name(),
                    agent.definition().binary(), agent.definition().label());
            if (verbose) {
                final var definition = agent.definition();
                out.println("             domains: " + String.join(", ", definition.allowedDomains()));
                out.println("             resume:  " + (definition.supportsResume() ? "yes" : "no"));
            }
        }
        out.flush();
        return 0;
    }
}
