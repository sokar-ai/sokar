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
public class GateBackupCommand implements Callable<Integer>,
        SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<file>", description = "Where to write the bundle.")
    private Path bundle;

    @Option(names = { "-p", "--project" }, paramLabel = "<file>",
            description = "Project file. Default: ${DEFAULT-VALUE}")
    private Path projectFile = Path.of("project.yml");

    @Option(names = { "-r", "--repository" }, paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the project's own.")
    private String repository;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {
            final Project project = GateSupport.project(projectFile);
            final org.fuin.sokar.core.project.Repository chosen =
                    GateSupport.repository(project, repository);
            final GitGate gate = GateSupport.gate(project, chosen, null, null);
            gate.backup(bundle);
            // Recorded here rather than inside the gate: the gate knows nothing about this
            // machine's directories, and a bundle written wherever an operator names it is
            // otherwise forgotten the moment this command returns.
            // Under the REPOSITORY'S key, not the project's. A bundle of one repository's mirror
            // recorded against the project would be offered as the project's own - and restoring
            // it would write one repository's history over another's, which is the only way this
            // command can destroy something.
            new BackupRecords(context.paths().backupRecords())
                    .add(GateSupport.recordKey(project.name(), chosen.name()), bundle,
                            gate.pending().size());
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
