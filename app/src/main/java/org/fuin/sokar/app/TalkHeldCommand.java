package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Lists what reached this task's mailbox and was not handed to the agent.
 * <p>
 * A held message is one nobody could attribute, or one a transport refused permanently. It is kept
 * rather than deleted, because that somebody tried is the information - and it is listed here rather
 * than left to be found with {@code ls}.
 */
@Command(name = "held",
        mixinStandardHelpOptions = true,
        description = "Lists messages that reached no agent, and are kept.")
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
        try (Stream<Path> entries = Files.list(mailbox.hold())) {
            final var messages = entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json")).sorted()
                    .toList();
            if (messages.isEmpty()) {
                out.println("nothing held");
            } else {
                for (final Path message : messages) {
                    out.println(message.getFileName() + "   "
                            + Files.size(message) + " bytes, kept as evidence");
                }
            }
        }
        out.flush();
        return 0;
    }
}
