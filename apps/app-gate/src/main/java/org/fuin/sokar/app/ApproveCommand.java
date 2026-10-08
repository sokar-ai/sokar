package org.fuin.sokar.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Brings the work of a task started in this checkout back into it, as the branch {@code sokar/<task>}.
 */
@Command(name = "approve",
        mixinStandardHelpOptions = true,
        description = "Brings the work of a task started in this checkout back into it as the branch sokar/<task>,"
                + " after showing what comes and asking. Run it in the checkout.")
public class ApproveCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<task>",
            description = "The task whose work to bring back. Default: the one started from this checkout.")
    private @Nullable String task;

    @Option(names = { "-y", "--yes" }, description = "Brings it back without asking, for a script.")
    private boolean yes;

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
        final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        return new CheckoutApproval(context, out, spec.commandLine().getErr()).run(Path.of("."), task, yes,
                question -> {
                    out.print(question + " ");
                    out.flush();
                    try {
                        return in.readLine();
                    } catch (IOException ex) {
                        return null;
                    }
                });
    }
}
