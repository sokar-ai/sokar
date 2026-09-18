package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.fuin.sokar.wire.Json;
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

    /** What a message from a person carries, where an agent's carries {@code ROLE_AGENT}. */
    public static final String ROLE = "ROLE_USER";

    private static final DateTimeFormatter FILE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC);

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Parameters(index = "1", paramLabel = "<peer>", description = "Who it is addressed to.")
    private String peer;

    @Option(names = "--kind", paramLabel = "<kind>",
            description = "question, answer, status, review-request or handover. Default: question")
    private String kind = "question";

    @Option(names = "--context", paramLabel = "<id>",
            description = "The conversation it belongs to. Default: a new one.")
    private String contextId;

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
        final Mailbox mailbox = new Mailbox(context.paths().mailbox(container));
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

        final Instant now = Instant.now();
        final String id = "person-" + UUID.randomUUID();
        final Map<String, Object> message = new LinkedHashMap<>();
        message.put("messageId", id);
        message.put("role", ROLE);
        message.put("contextId", contextId == null ? "c-" + UUID.randomUUID() : contextId);
        message.put("parts",
                List.of(new LinkedHashMap<>(Map.of("text", text, "mediaType", "text/plain"))));
        final Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("kind", kind);
        metadata.put("to", peer);
        message.put("metadata", metadata);

        final String name = FILE_TIME.format(now) + "--" + id + ".json";
        // Into the outbox through tmp and a rename, exactly as an agent has to write: a message is
        // complete when it is renamed, and nothing here gets a shortcut the agent does not have.
        final Path staged = mailbox.outboxTmp().resolve(name);
        Files.writeString(staged, Json.write(message), StandardCharsets.UTF_8);
        Files.move(staged, mailbox.outboxNew().resolve(name), StandardCopyOption.ATOMIC_MOVE);
        out.println("written   " + name + " - the next pass puts it through the filter");
        out.flush();
        return 0;
    }
}
