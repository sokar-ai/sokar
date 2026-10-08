package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Hands a file on this machine to a running task, where the agent reads it in {@code /sokar/files}.
 * <p>
 * The same operation the daemon serves as HandIn, run here at once since the file is here: placed whole, read-only to
 * the agent, and written down - who, when, the name, the size and the sha256, never the content.
 */
@Command(name = "give",
        mixinStandardHelpOptions = true,
        description = "Hands a file to a running task: it appears, read-only, in /sokar/files.")
public class TaskGiveCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Parameters(index = "0", paramLabel = "TASK", description = "A running task, as shown by 'sokar task list'.")
    private String container;

    @Parameters(index = "1", paramLabel = "FILE", description = "The file on this machine.")
    private Path file;

    @Option(names = "--as", paramLabel = "NAME",
            description = "Its name in /sokar/files. Default: the file's own name.")
    private @Nullable String name;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public List<String> candidates() {
        return TaskCandidates.running(context);
    }

    @Override
    public String candidateLabel() {
        return "running tasks";
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (!TaskCandidates.running(context).contains(container)) {
            err.println("sokar: no running task named '" + container + "' - a file goes only to a running task;"
                    + " 'sokar task list' shows what is there");
            err.flush();
            return 69;
        }
        if (!Files.isRegularFile(file)) {
            err.println("sokar: " + file + " is not a file here");
            err.flush();
            return 66;
        }
        final String as = name == null ? String.valueOf(file.getFileName()) : name;
        try {
            final HandIns.HandedFile given = HandIns.of(context).give(container, as, file,
                    System.getProperty("user.name", ""));
            out.println("given     " + container + ": /sokar/files/" + given.name() + ", " + given.bytes()
                    + " bytes, sha256 " + given.sha256());
            out.flush();
            // Its agent, when it waits at its prompt; otherwise the daemon's next pass tells it.
            new AgentWake(context).announceFiles(container);
            return 0;
        } catch (HandIns.Refused refused) {
            err.println("sokar: " + refused.getMessage());
            err.flush();
            return 65;
        } catch (IOException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
