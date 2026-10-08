package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Puts a person's own words straight into a task's inbox, where its agent reads.
 * <p>
 * Where {@code say} speaks for a person in a task's conversation with a peer, this speaks to the task itself. The
 * text is read from standard input, as {@code say} reads it: a process list is world-readable.
 */
@Command(name = "tell",
        mixinStandardHelpOptions = true,
        description = "Puts your words into a task's inbox, for its agent. The text is read from stdin.")
public class TalkTellCommand implements Callable<Integer>, SokarFactory.ContextAware {

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
        // By the container's name or the task's own, as every task command takes it.
        container = TaskTarget.spelled(context, container);
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        if (!mailbox.exists()) {
            err.println("sokar: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        final String text = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            err.println("sokar: nothing to tell - the text is read from standard input");
            err.flush();
            return 1;
        }
        out.println("told      " + new PersonNote().tell(mailbox, text, null) + " - in the task's inbox now");
        // A person's word to this one task: its agent at rest is told it is there, as for a message that names it.
        if (new AgentWake(context).announce(mailbox, container, null)) {
            out.println("woken     its agent, at its prompt");
        }
        out.flush();
        return 0;
    }
}
