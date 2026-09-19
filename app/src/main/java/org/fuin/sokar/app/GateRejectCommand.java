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
 * Discards a pending push without forwarding it.
 */
@Command(name = "reject",
        mixinStandardHelpOptions = true,
        description = "Discards a pending push without forwarding it.")
public class GateRejectCommand implements Callable<Integer> {

    @Option(names = { "-p", "--project" }, paramLabel = "<name>", required = true,
            description = "Project name, as 'sokar project list' prints it.")
    private String projectName;

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the project's own.")
    private String repository;

    @Option(names = "--upstream", paramLabel = "<url>",
            description = "Upstream repository to forward approved pushes to.")
    private String upstream;

    @Parameters(index = "0", paramLabel = "<name>", description = "Name of the pending push.")
    private String name;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            final Project project = GateSupport.byName(projectName);
            final GitGate gate = GateSupport.gate(project,
                    GateSupport.repository(project, repository), upstream, null);
            gate.initialize();
            gate.reject(name);
            out.println("discarded " + name);
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
