package org.fuin.sokar.agent.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.sokar.clearance.varlink.VarlinkServer;

/**
 * Serves one agent over varlink.
 * <p>
 * Linked into the agent's own binary, not into Sokar. An agent's {@code main} is therefore three
 * lines, and every agent answers the same four calls in the same way - the differences live in the
 * {@link Agent} it was given, which is where they belong.
 */
public class AgentServer implements AutoCloseable {

    private final Agent agent;

    private final VarlinkServer server;

    /**
     * Binds the socket and registers the protocol.
     *
     * @param agent The agent to serve.
     * @param socket Where to create the socket.
     */
    public AgentServer(Agent agent, Path socket) {

        this.agent = agent;
        this.server = new VarlinkServer(socket, AgentProtocol.INTERFACE);

        server.method("Describe", (parameters, replies) -> {
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("protocolVersion", Integer.valueOf(AgentProtocol.VERSION));
            answer.put("definition", AgentDefinitionJson.write(agent.definition()));
            replies.last(answer);
        });

        server.method("ExtractCredential", (parameters, replies) -> {
            final Object directory = parameters.get("configDirectory");
            if (!(directory instanceof String path)) {
                throw new AgentException("ExtractCredential needs a configDirectory");
            }
            final Optional<Credential> credential =
                    agent.credentialExtractor().extract(Path.of(path));
            final Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("found", Boolean.valueOf(credential.isPresent()));
            credential.ifPresent(value -> {
                answer.put("type", value.type());
                answer.put("secret", value.secret());
                answer.put("attributes", value.attributes());
            });
            replies.last(answer);
        });

        server.method("BuildCommand", (parameters, replies) -> {
            final Object prompt = parameters.get("prompt");
            if (!(prompt instanceof String text)) {
                throw new AgentException("BuildCommand needs a prompt");
            }
            final RunRequest request = new RunRequest(text,
                    optional(parameters, "model"),
                    integer(parameters, "maxTurns"),
                    optional(parameters, "resumeSession"),
                    Boolean.TRUE.equals(parameters.get("verbose")),
                    Boolean.TRUE.equals(parameters.get("machineReadable")));
            replies.last(Map.of("command", agent.headlessCommand(request)));
        });

        server.method("FormatLog", (parameters, replies) -> {
            final Object source = parameters.get("source");
            if (!(source instanceof String path)) {
                throw new AgentException("FormatLog needs a source");
            }
            if (!replies.streaming()) {
                // Answering a non-streaming FormatLog with one line would look like it worked and
                // then deliver nothing.
                throw new AgentException("FormatLog requires more=true");
            }
            final List<String> formatted = new ArrayList<>();
            for (final String line : java.nio.file.Files.readAllLines(Path.of(path))) {
                final String shown = agent.logFormatter().format(line);
                if (shown != null) {
                    formatted.add(shown);
                }
            }
            for (int i = 0; i < formatted.size() - 1; i++) {
                replies.more(Map.of("line", formatted.get(i)));
            }
            replies.last(formatted.isEmpty() ? Map.of()
                    : Map.of("line", formatted.get(formatted.size() - 1)));
        });
    }

    /**
     * Returns the agent being served.
     *
     * @return The agent.
     */
    public Agent agent() {
        return agent;
    }

    /**
     * Returns the socket path.
     *
     * @return Path Sokar connects to.
     */
    public Path socketPath() {
        return server.socketPath();
    }

    /**
     * Serves until the process is stopped.
     */
    public void serve() {
        server.run();
    }

    @Override
    public void close() {
        server.close();
    }

    private static String optional(Map<String, Object> parameters, String key) {
        return parameters.get(key) instanceof String value && !value.isBlank() ? value : null;
    }

    private static Integer integer(Map<String, Object> parameters, String key) {
        return parameters.get(key) instanceof Number value ? Integer.valueOf(value.intValue()) : null;
    }
}
