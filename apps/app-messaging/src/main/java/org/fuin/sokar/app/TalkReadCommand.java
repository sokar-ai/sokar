package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Shows one held message, so that releasing it is a decision about what it says.
 * <p>
 * Only what waits in the hold list, as {@code talk release} finds it: a message the filter refused is
 * never shown here, because it is kept in clear text with whatever made the filter refuse it.
 */
@Command(name = "read",
        mixinStandardHelpOptions = true,
        description = "Shows a held or refused message in full: who, when, to whom, why, and what it says.")
public class TalkReadCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Parameters(index = "1", paramLabel = "<id>",
            description = "Message id or file name, as 'talk held' lists it.")
    private String id;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
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
        final MessageRead.Held held = new MessageRead().read(mailbox, id);
        return switch (held.outcome()) {
            case FOUND -> {
                // Every field through 'shown': none of it was cleared by anybody, and a terminal that
                // interprets what a held message carries has let the message act before a person decided.
                out.println("message   " + shown(held.message()));
                out.println("standing  " + held.standing());
                if (!held.direction().isEmpty()) {
                    out.println("going     " + ("in".equals(held.direction()) ? "in, to this task" : "out, from this task"));
                }
                out.println("from      " + ("ROLE_USER".equals(held.role()) ? "a person" : "the task"));
                out.println("when      " + (held.at().isEmpty() ? "-" : shown(held.at())));
                out.println("peer      " + (held.peer().isEmpty() ? "-" : shown(held.peer())));
                out.println("kind      " + (held.kind().isEmpty() ? "-" : shown(held.kind())));
                out.println("held      " + (held.reason().isEmpty() ? "-" : shown(held.reason())));
                out.println();
                held.text().forEach(part -> part.lines().map(TalkReadCommand::shown).forEach(out::println));
                out.flush();
                yield 0;
            }
            case AMBIGUOUS -> {
                err.println("sokar: more than one held message answers to '" + id + "'; name the file instead");
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

    /**
     * Makes text safe to print: every control character shows as its escape instead of acting.
     * <p>
     * A held message is evidence, not content. An escape sequence in it would otherwise move the cursor,
     * rewrite what is already on the screen or set the window title - the message acting on the person
     * about to judge it.
     *
     * @param text Text from a message nobody has cleared.
     * @return The same text, with C0 and C1 controls and DEL written as {@code \xNN}; tabs kept.
     */
    static String shown(final String text) {
        final StringBuilder safe = new StringBuilder(text.length());
        text.codePoints().forEach(point -> {
            if (point == '\t' || point >= 0x20 && point < 0x7f || point > 0x9f) {
                safe.appendCodePoint(point);
            } else {
                safe.append(String.format("\\x%02x", point));
            }
        });
        return safe.toString();
    }
}
