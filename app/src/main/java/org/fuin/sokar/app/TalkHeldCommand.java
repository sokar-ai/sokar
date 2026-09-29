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
 * Lists what in this task's mailbox waits for a person, or is kept because nobody may send it.
 * <p>
 * The same list the daemon's {@code Held} answers, found exactly as {@code talk read} and
 * {@code talk release} find a message: held ones, ones the filter refused, and ones it could not
 * check - each with where it stands and why, never with what it says. Reading one is
 * {@code talk read}.
 */
@Command(name = "held",
        mixinStandardHelpOptions = true,
        description = "Lists the messages waiting for a person, and the ones kept that nobody may send.")
public class TalkHeldCommand implements Callable<Integer>, SokarFactory.ContextAware {

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
    public Integer call() throws IOException {
        final PrintWriter out = spec.commandLine().getOut();
        final Mailbox mailbox = new Mailbox(context.paths().mailbox(container));
        if (!mailbox.exists()) {
            spec.commandLine().getErr().println("sokar: " + container + " has no mailbox");
            spec.commandLine().getErr().flush();
            return 1;
        }
        final List<MessageRead.Held> listed = new MessageRead().list(mailbox);
        if (listed.isEmpty()) {
            out.println("nothing held");
        }
        for (final MessageRead.Held held : listed) {
            // Everything shown is escaped: peer and reason come from files a task or a filter wrote.
            out.println(TalkReadCommand.shown(held.message()) + "   " + held.standing()
                    + (held.direction().isEmpty() ? "" : "   " + held.direction())
                    + (held.peer().isEmpty() ? "" : "   " + TalkReadCommand.shown(held.peer()))
                    + (held.reason().isEmpty() ? "" : "   " + TalkReadCommand.shown(held.reason())));
        }
        out.flush();
        return 0;
    }
}
