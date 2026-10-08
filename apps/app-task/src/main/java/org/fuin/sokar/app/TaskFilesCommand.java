package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Prints every file handed to a task under that name, taken back or replaced too - also once the task is gone.
 * <p>
 * The record names the files and never holds their content; it says what Sokar did, so a file copied into a
 * container by other means is not in it.
 */
@Command(name = "files",
        mixinStandardHelpOptions = true,
        description = "Shows every file handed to a task, also after it is gone: who, when, size and sha256.")
public class TaskFilesCommand implements Callable<Integer>, SokarFactory.ContextAware, Suggests {

    @Parameters(index = "0", paramLabel = "TASK", description = "A task's name, as 'sokar task list' showed it.")
    private String container;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public List<String> candidates() {
        return TaskCandidates.all(context);
    }

    @Override
    public String candidateLabel() {
        return "tasks";
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final List<HandIns.Entry> record;
        try {
            record = HandIns.of(context).record(container);
        } catch (IOException ex) {
            err.println("sokar: could not read what was handed to " + container + ": " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (record.isEmpty()) {
            out.println("nothing was handed to " + container);
            out.flush();
            return 0;
        }
        out.printf("%-20s  %-10s  %-12s  %-12s  %10s  %-16s  %s%n", "WHEN", "EVENT", "BY", "RUN", "BYTES", "SHA256",
                "NAME");
        for (final HandIns.Entry entry : record) {
            final HandIns.HandedFile file = entry.file();
            out.printf("%-20s  %-10s  %-12s  %-12s  %10d  %-16s  %s%n", file.at(), entry.event(), file.by(),
                    file.run().length() > 12 ? file.run().substring(0, 12) : file.run(), file.bytes(),
                    file.sha256().substring(0, Math.min(16, file.sha256().length())), file.name());
        }
        out.println();
        out.println("What Sokar handed in. A file copied into a container by other means, such as podman cp, is not"
                + " here.");
        out.flush();
        return 0;
    }
}
