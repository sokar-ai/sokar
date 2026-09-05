package org.fuin.sokar.agent.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.clearance.varlink.VarlinkClient;

/**
 * An agent binary installed on this machine.
 * <p>
 * Sokar never links an agent, so everything it knows comes from asking one. The process is started
 * on demand and stopped when the work is done: agents are used a handful of times per task, and a
 * resident process per installed agent would be cost with no matching benefit.
 * <p>
 * <strong>The protocol version is checked before anything else.</strong> Sokar and the agents ship
 * as separate packages, so an untested combination is a matter of time; refusing it with a clear
 * message beats reading a field that means something else now.
 */
public class InstalledAgent implements AutoCloseable {

    /** How long to wait for a spawned agent to create its socket. */
    private static final long READY_TIMEOUT_SECONDS = 10;

    private final Path executable;

    private final Path socket;

    private final Process process;

    private final VarlinkClient client;

    private final AgentDefinition definition;

    /**
     * Starts an agent binary and completes its handshake.
     *
     * @param executable The agent binary.
     * @param socketDirectory Directory to create the socket in.
     * @throws AgentException If the agent cannot be started, or speaks a protocol this Sokar does
     *         not understand.
     */
    public InstalledAgent(Path executable, Path socketDirectory) {

        this.executable = executable;
        this.socket = socketDirectory.resolve(executable.getFileName() + "-"
                + ProcessHandle.current().pid() + ".sock");

        try {
            Files.createDirectories(socketDirectory);
            Files.deleteIfExists(socket);
            this.process = new ProcessBuilder(executable.toString(), "serve", socket.toString())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ex) {
            throw new AgentException("Cannot start " + executable, ex);
        }

        try {
            awaitReady();
            this.client = new VarlinkClient(socket);
            this.definition = handshake();
        } catch (RuntimeException ex) {
            process.destroyForcibly();
            throw ex;
        }
    }

    private void awaitReady() {

        // Waiting for the line the agent prints rather than polling for the socket file: the file
        // appears before bind() returns, so polling can connect to a socket nobody is listening on.
        final StringBuilder said = new StringBuilder();
        boolean exited = false;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            final long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
            while (System.nanoTime() < deadline) {
                if (reader.ready()) {
                    final String line = reader.readLine();
                    if (line == null) {
                        exited = true;
                        break;
                    }
                    if (line.startsWith("ready ")) {
                        return;
                    }
                    // Whatever it said instead is the diagnostic worth reporting.
                    said.append(said.isEmpty() ? "" : "; ").append(line.strip());
                }
                if (!process.isAlive()) {
                    exited = true;
                    break;
                }
                Thread.onSpinWait();
            }
        } catch (IOException ex) {
            throw new AgentException("Cannot read from " + executable, ex);
        }

        process.destroyForcibly();

        // A binary that died immediately and one that hung are different problems, and saying
        // "did not become ready within 10 seconds" about the first sends an operator looking for
        // a slow agent when they have a crashing one.
        if (exited) {
            throw new AgentException(executable + " exited without becoming ready"
                    + (said.isEmpty() ? "" : ", saying: " + said));
        }
        throw new AgentException(executable + " did not become ready within "
                + READY_TIMEOUT_SECONDS + " seconds");
    }

    private AgentDefinition handshake() {

        final Map<String, Object> answer = client.call(AgentProtocol.DESCRIBE, Map.of());

        final int version = answer.get("protocolVersion") instanceof Number number
                ? number.intValue() : -1;
        if (version != AgentProtocol.VERSION) {
            throw new AgentException(executable + " speaks agent protocol " + version
                    + ", this build of Sokar speaks " + AgentProtocol.VERSION
                    + ". Install a matching version of one of them.");
        }
        if (!(answer.get("definition") instanceof Map<?, ?> definition)) {
            throw new AgentException(executable + " returned no definition");
        }
        return AgentDefinitionJson.read(definition);
    }

    /**
     * Returns the definition this agent reported.
     *
     * @return The definition.
     */
    public AgentDefinition definition() {
        return definition;
    }

    /**
     * Returns the agent's name.
     *
     * @return Name.
     */
    public String name() {
        return definition.name();
    }

    /**
     * Returns the binary this agent was loaded from.
     *
     * @return Executable path.
     */
    public Path executable() {
        return executable;
    }

    /**
     * Asks the agent to read its credential.
     *
     * @param configDirectory Directory the agent wrote its config into.
     * @return The credential, or empty if it has not been logged in.
     */
    public Optional<Credential> extractCredential(Path configDirectory) {

        final Map<String, Object> answer = client.call(AgentProtocol.EXTRACT_CREDENTIAL,
                Map.of("configDirectory", configDirectory.toString()));

        if (!Boolean.TRUE.equals(answer.get("found"))) {
            return Optional.empty();
        }
        return Optional.of(new Credential(
                String.valueOf(answer.get("type")),
                String.valueOf(answer.get("secret")),
                attributes(answer.get("attributes"))));
    }

    /**
     * Asks the agent what it needs placed in a container before it starts.
     *
     * @param context What the agent is told about this task.
     * @return Files to write, possibly empty.
     */
    public List<ContainerFile> containerSetup(SetupContext context) {

        final Map<String, Object> answer =
                client.call(AgentProtocol.CONTAINER_SETUP, context.parameters());

        final List<ContainerFile> files = new java.util.ArrayList<>();
        if (answer.get("files") instanceof List<?> list) {
            for (final Object element : list) {
                if (element instanceof Map<?, ?> entry) {
                    files.add(new ContainerFile(String.valueOf(entry.get("path")),
                            String.valueOf(entry.get("content")),
                            Boolean.TRUE.equals(entry.get("ownerOnly"))));
                }
            }
        }
        return List.copyOf(files);
    }

    /**
     * Asks the agent to build a command line.
     *
     * @param request What to run.
     * @return Command and arguments.
     */
    public List<String> buildCommand(RunRequest request) {

        final Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("prompt", request.prompt());
        if (request.model() != null) {
            parameters.put("model", request.model());
        }
        if (request.maxTurns() != null) {
            parameters.put("maxTurns", request.maxTurns());
        }
        if (request.resumeSession() != null) {
            parameters.put("resumeSession", request.resumeSession());
        }
        parameters.put("verbose", Boolean.valueOf(request.verbose()));
        parameters.put("machineReadable", Boolean.valueOf(request.machineReadable()));

        final Object command = client.call(AgentProtocol.BUILD_COMMAND, parameters).get("command");
        if (!(command instanceof List<?> list)) {
            throw new AgentException(name() + " returned no command");
        }
        final List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return List.copyOf(result);
    }

    /**
     * Asks the agent to format a log file.
     *
     * @param source File holding the agent's raw output.
     * @param line Receives each formatted line.
     */
    public void formatLog(Path source, java.util.function.Consumer<String> line) {
        client.callMore(AgentProtocol.FORMAT_LOG, Map.of("source", source.toString()), reply -> {
            if (reply.get("line") instanceof String text) {
                line.accept(text);
            }
            return true;
        });
    }

    private static Map<String, String> attributes(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        final Map<String, String> result = new java.util.LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (IOException ex) {
            // Shutting down.
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The next start unlinks first.
        }
    }
}
