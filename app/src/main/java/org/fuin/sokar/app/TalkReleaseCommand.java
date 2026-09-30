package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Sends a held message on its way, or refuses it for good.
 * <p>
 * The two halves of one decision, in one place, because an operator looking at a hold list is
 * deciding between them and not looking for two commands.
 */
@Command(name = "release",
        mixinStandardHelpOptions = true,
        description = "Releases a held message, or one the filter refused, after reading it; or refuses it.")
public class TalkReleaseCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Parameters(index = "1", paramLabel = "<id>",
            description = "Message id or file name, as 'talk held' lists it.")
    private String id;

    @Option(names = "--refuse", description = "Refuses it for good. The sender is told.")
    private boolean refuse;

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
        final PrintWriter err = spec.commandLine().getErr();
        final Mailbox mailbox = new Mailbox(context.paths().mailbox(container));
        if (!mailbox.exists()) {
            err.println("sokar: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        final MessageRelease.Result result = new MessageRelease().decide(mailbox, id, refuse,
                MessageRelease.peersOf(context, container));
        return switch (result.outcome()) {
            case RELEASED -> {
                out.println("released  " + result.message() + " - " + (result.detail().isEmpty()
                        ? "the next pass sends it; only a peer set to deny still refuses it" : result.detail()));
                out.flush();
                yield 0;
            }
            case DELIVERED_DESPITE_FILTER -> {
                // Said as what it is, never as 'released': the filter refused it and a person sent it.
                out.println("delivered " + result.message() + " despite the filter's refusal, as you decided;"
                        + " a peer that checks what it receives may refuse it again");
                out.flush();
                yield 0;
            }
            case NOT_DELIVERABLE -> {
                err.println("sokar: " + result.message() + " cannot be sent: " + result.detail());
                err.flush();
                yield 1;
            }
            case REFUSED -> {
                out.println("refused   " + result.message() + " - the sender has been told");
                out.flush();
                yield 0;
            }
            case AMBIGUOUS -> {
                err.println("sokar: more than one held message answers to '" + id
                        + "'; name the file instead");
                err.flush();
                yield 1;
            }
            case NO_SUCH_MESSAGE -> {
                err.println("sokar: nothing held in " + container + " is called '" + id + "'");
                err.flush();
                yield 1;
            }
        };
    }
}
