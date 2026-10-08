package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Writes a person's own message into a task's conversation.
 * <p>
 * <strong>The text is read from standard input</strong>, the way {@code vault put} reads a secret: a
 * process list is world-readable, and nobody can promise what somebody will paste into a message to
 * an agent.
 * <p>
 * <strong>It goes out through the same pipeline as the agent's own messages</strong> - the outbox,
 * the filter, the queue - because a person is not a reason to skip the check that a message carries
 * nothing it should not. What differs is {@code role}, which says a person wrote it, so the reader
 * can tell.
 */
@Command(name = "say",
        mixinStandardHelpOptions = true,
        description = "Writes a message into a conversation. The text is read from stdin.")
public class TalkSayCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Parameters(index = "1", paramLabel = "<peer>", description = "Who it is addressed to.")
    private String peer;

    @Option(names = "--kind", paramLabel = "<kind>",
            description = "question, answer, status, review-request or handover. Default: question")
    private String kind = "question";

    @Option(names = "--context", paramLabel = "<id>",
            description = "The conversation it belongs to. Default: a new one.")
    private @Nullable String contextId;

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
            err.println("sokar: nothing to say - the text is read from standard input");
            err.flush();
            return 1;
        }

        final String refused = MessageSay.refusal(MessageRelease.peersOf(context, container), peer);
        if (refused != null) {
            err.println("sokar: " + refused);
            err.flush();
            return 1;
        }
        final String name = new MessageSay().write(mailbox, peer, kind, contextId, text);
        out.println("written   " + name + " - the next pass puts it through the filter");
        out.flush();
        return 0;
    }
}
