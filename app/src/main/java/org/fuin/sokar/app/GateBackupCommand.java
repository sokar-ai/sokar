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
 * Writes a git bundle holding the whole mirror, pending pushes included.
 */
@Command(name = "backup",
        mixinStandardHelpOptions = true,
        description = "Writes the mirror to a single verifiable bundle file.")
public class GateBackupCommand implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "<file>", description = "Where to write the bundle.")
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
            gate.backup(bundle);
            out.println("backed up " + gate.mirror() + " to " + bundle);
            out.println("verified  " + gate.verifyBackup(bundle));
            out.flush();
            return 0;
        } catch (GateException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
