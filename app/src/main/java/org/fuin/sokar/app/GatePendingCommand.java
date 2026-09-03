package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.gate.GateException;
import org.fuin.sokar.gate.GitGate;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Lists what the agent pushed and nobody has reviewed.
 */
@Command(name = "pending",
        mixinStandardHelpOptions = true,
        description = "Lists what the agent pushed and nobody has reviewed.")
public class GatePendingCommand implements Callable<Integer> {

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream repository to forward approved pushes to.")
    private String upstream;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            final Project project = GateSupport.project(projectFile);
            final GitGate gate = GateSupport.gate(project, upstream);
            gate.initialise();
            final var pending = gate.pending();
            if (pending.isEmpty()) {
                out.println("nothing pending in " + gate.mirror());
            } else {
                out.println("mode      " + gate.mode().name().toLowerCase());
                pending.forEach(name -> out.println("pending   " + name));
            }
            out.flush();
            return 0;
        } catch (GateException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        } catch (RuntimeException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 2;
        }
    }
}
