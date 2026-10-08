package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Says whether a task's record still holds together.
 * <p>
 * The question an operator asks after something went wrong: was this mailbox tampered with. It is
 * answered by walking rather than by trusting a flag somebody could set.
 */
@Command(name = "verify",
        mixinStandardHelpOptions = true,
        description = "Walks the record and names the first entry that does not check out.")
public class TalkVerifyCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        if (!mailbox.exists()) {
            err.println("sokar: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        final MessageVerify.Report report = new MessageVerify().check(mailbox,
                AllowedSigners.read(context.paths().messaging().allowedSigners()));
        out.println("record    " + report.lines() + " line(s), " + report.signatures()
                + " signature(s) checked");
        if (report.faults().isEmpty()) {
            out.println("intact");
            out.flush();
            return 0;
        }
        out.flush();
        report.faults().forEach(fault ->
                err.println("broken    " + fault.what() + " - " + fault.detail()));
        err.flush();
        // A failure, because this command exists to be run by something that acts on the answer.
        return 1;
    }
}
