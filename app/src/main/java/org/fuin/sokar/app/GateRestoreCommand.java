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
 * Restores a mirror from a bundle.
 * <p>
 * Refuses to write over an existing mirror rather than merging into it, because a restore that
 * silently discarded whatever the agent pushed since the backup would be worse than no restore.
 */
@Command(name = "restore",
        mixinStandardHelpOptions = true,
        description = "Restores the mirror from a bundle, refusing to overwrite an existing one.")
public class GateRestoreCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<file>", description = "Bundle to restore from.")
    private Path bundle;

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            final Project project = GateSupport.project(projectFile);
            final GitGate gate = GateSupport.gate(project, null);
            gate.restore(bundle);
            out.println("restored  " + gate.mirror() + " from " + bundle);
            out.println("pending   " + gate.pending().size() + " push(es) recovered");
            out.flush();
            return 0;
        } catch (GateException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
