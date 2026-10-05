package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.runtime.Podman;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Builds a project's task image without starting anything.
 * <p>
 * The first task in a project otherwise spends minutes building before any work begins, and there
 * was no way to pay that cost deliberately - at the end of a day rather than at the start of one.
 */
@Command(name = "prepare",
        mixinStandardHelpOptions = true,
        description = "Builds this project's task image without starting a task.")
public class TaskPrepareCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = { "-p", "--project" }, paramLabel = "<name>", required = true,
            description = "Project name, as 'sokar project list' prints it.")
    private String projectName;

    @Option(names = "--agent", paramLabel = "<name>",
            description = "Whose tooling to install. Default: the only one installed.")
    private @Nullable String agentName;

    @Option(names = "--rebuild", paramLabel = "<depth>",
            description = "How much to discard: ${COMPLETION-CANDIDATES}. Default:"
                    + " ${DEFAULT-VALUE}.")
    private Podman.Rebuild rebuild = Podman.Rebuild.CACHED;

    @Option(names = "--dry-run", description = "Says what it would do and does nothing.")
    private boolean dryRun;

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

        final Preparation.Result result = Preparation.prepare(context,
                ProjectSource.require(context, projectName), agentName,
                rebuild, dryRun, out);
        out.flush();

        if (result.outcome() == Preparation.Outcome.PREPARED
                || result.outcome() == Preparation.Outcome.PREVIEWED) {
            return 0;
        }
        err.println("sokar: " + result.detail());
        err.flush();
        return result.outcome() == Preparation.Outcome.NO_SUCH_PROJECT ? 69 : 70;
    }
}
