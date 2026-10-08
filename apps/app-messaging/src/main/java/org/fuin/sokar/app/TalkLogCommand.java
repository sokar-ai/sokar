package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;
import picocli.CommandLine.Model.CommandSpec;

/**
 * Shows a task's conversation as it happened: what was written, sent, held, refused, delivered and read, in order.
 * <p>
 * Read from the task's own record, line by line as it was written, so following it is following the conversation.
 * Nothing a message says is shown here - only what happened to it, with whom and when; {@code talk read} shows a
 * message itself. The daemon streams the same as {@code Talk}; this is the terminal's way to the same answer.
 */
@Command(name = "log",
        mixinStandardHelpOptions = true,
        description = "Shows what happened to a task's messages, in order: written, sent, held, refused, delivered,"
                + " read - with whom and when, never what they say.")
public class TalkLogCommand implements Callable<Integer>, SokarFactory.ContextAware {

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
        final java.util.List<Map<String, Object>> entries = new MessageRecord(mailbox).entries();
        if (entries.isEmpty()) {
            out.println("no message yet");
            out.flush();
            return 0;
        }
        out.printf("%-24s %-12s %-20s %-36s %s%n", "AT", "EVENT", "PEER", "MESSAGE", "DETAIL");
        for (final Map<String, Object> entry : entries) {
            out.printf("%-24s %-12s %-20s %-36s %s%n", text(entry, "at"), text(entry, "event"), text(entry, "peer"),
                    text(entry, "message"), text(entry, "detail"));
        }
        out.flush();
        return 0;
    }

    private static String text(final Map<String, Object> entry, final String key) {
        // Escaped: a peer, a file name and a reason come from files a task or a filter wrote.
        final Object value = entry.get(key);
        return value == null || String.valueOf(value).isEmpty() ? "-" : TalkReadCommand.shown(String.valueOf(value));
    }
}
