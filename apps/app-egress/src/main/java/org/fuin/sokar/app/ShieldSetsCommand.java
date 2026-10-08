package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.shield.EgressSet;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Lists the curated egress sets a project can opt into.
 * <p>
 * Sets exist so that "reaching npm" is written down once instead of re-derived per project. That
 * only works if an operator can find out what the names are without reading the packaged files,
 * which is what this is for.
 */
@Command(name = "sets",
        mixinStandardHelpOptions = true,
        description = "Lists the curated egress sets a project can name in project.yml.")
public class ShieldSetsCommand implements Callable<Integer>, SokarFactory.ContextAware {

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }


    @Option(names = "--verbose", description = "Also show the hosts each set grants.")
    private boolean verbose;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final var directory = context.paths().egress().egressSets();
        final var sets = directory.all();

        if (sets.isEmpty()) {
            out.println("No egress sets are installed. Looked in:");
            directory.locations().forEach(location -> out.println("  " + location));
            out.flush();
            return 0;
        }

        for (final EgressSet set : sets.values()) {
            out.printf("%-20s %s (%d hosts)%n", set.name(), set.label(), set.domains().size());
            if (verbose) {
                set.domains().forEach(domain -> out.println("                     " + domain));
            }
        }

        out.println();
        out.println("Name them in project.yml:");
        out.println();
        out.println("  egress:");
        out.println("    sets: [" + String.join(", ",
                sets.keySet().stream().limit(2).toList()) + "]");
        out.println("    domains: [\"nexus.corp.example\"]   # a private mirror, if you have one");
        out.flush();
        return 0;
    }

    /**
     * Returns where sets are looked for, for a caller that wants to say so.
     *
     * @param context The machine.
     * @return Locations, most specific first.
     */
    static java.util.List<Path> locations(SokarContext context) {
        return context.paths().egress().egressSets().locations();
    }
}
