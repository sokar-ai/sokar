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
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.jspecify.annotations.Nullable;

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
    static final long READY_TIMEOUT_SECONDS = 10;

    private final Path executable;

    private final Path socket;

    private final Process process;

    private final VarlinkClient client;

    private final AgentDefinition definition;

    /** What the agent wrote after its ready line, the last few thousand characters of it. */
    private final StringBuffer afterReady = new StringBuffer();

    /**
     * Returns a socket path no other lookup uses, in this process or another.
     * <p>
     * Named after the pid alone, two lookups at once shared it: the second adapter found the first one's socket live
     * and exited, the lookup listed no agent, and one finishing deleted the path from under one still starting.
     *
     * @param executable The agent binary.
     * @param socketDirectory Directory the socket is created in.
     * @return The path.
     */
    static Path socketFor(final Path executable, final Path socketDirectory) {
        return socketDirectory.resolve(executable.getFileName() + "-" + ProcessHandle.current().pid() + "-"
                + LOOKUPS.incrementAndGet() + ".sock");
    }

    /** Counts the lookups of this process, so each has a socket of its own. */
    private static final java.util.concurrent.atomic.AtomicLong LOOKUPS = new java.util.concurrent.atomic.AtomicLong();

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
        this.socket = socketFor(executable, socketDirectory);

        try {
            Files.createDirectories(socketDirectory);
            Files.deleteIfExists(socket);
            this.process = new ProcessBuilder(executable.toString(), "serve", socket.toString())
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ex) {
            throw new AgentException("Cannot start " + executable, ex);
        }

        VarlinkClient opened = null;
        try {
            awaitReady();
            opened = new VarlinkClient(socket);
            this.client = opened;
            this.definition = bounded(this::handshake);
        } catch (RuntimeException ex) {
            // Everything it left goes, and the failure is the agent's: as a VarlinkException it escaped the list, which
            // threw the whole lookup away and left the agents started before it running.
            process.destroyForcibly();
            if (opened != null) {
                try {
                    opened.close();
                } catch (IOException closing) {
                    // Closing a connection to a process just ended.
                }
            }
            try {
                Files.deleteIfExists(socket);
            } catch (IOException deleting) {
                // A socket left in the runtime directory is named for this lookup alone.
            }
            throw ex instanceof AgentException agent ? agent
                    : new AgentException(executable + " failed to start: " + ex.getMessage(), ex);
        }
    }

    /**
     * Keeps reading what the agent writes after its ready line, on a thread of its own, until it ends.
     * <p>
     * Read rather than closed: closed, an agent that wrote one more line met a broken pipe, and what it wrote is what
     * says why it did not answer. The last few thousand characters are kept for that.
     */
    private void drain(BufferedReader reader) {
        Thread.ofPlatform().daemon().name("sokar-agent-output").start(() -> {
            try (reader) {
                final char[] chunk = new char[1024];
                int read;
                while ((read = reader.read(chunk)) >= 0) {
                    synchronized (afterReady) {
                        afterReady.append(chunk, 0, read);
                        if (afterReady.length() > 4096) {
                            afterReady.delete(0, afterReady.length() - 4096);
                        }
                    }
                }
            } catch (IOException ex) {
                // The agent ended, or was ended.
            }
        });
    }

    private void awaitReady() {

        // Waiting for the line the agent prints rather than polling for the socket file: the file
        // appears before bind() returns, so polling can connect to a socket nobody is listening on.
        final StringBuilder said = new StringBuilder();
        boolean exited = false;

        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        boolean draining = false;
        try {
            final long deadline = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS);
            // Character by character, only what is there: a whole line was read once anything was ready, and an
            // adapter that printed a word with no line end and hung held every task start for ever.
            final StringBuilder line = new StringBuilder();
            while (System.nanoTime() < deadline) {
                while (reader.ready()) {
                    final int c = reader.read();
                    if (c < 0) {
                        exited = true;
                        break;
                    }
                    if (c != '\n') {
                        if (line.length() < 4096) {
                            line.append((char) c);
                        }
                        continue;
                    }
                    if (line.toString().startsWith("ready ")) {
                        drain(reader);
                        draining = true;
                        return;
                    }
                    // Whatever it said instead is the diagnostic worth reporting.
                    said.append(said.isEmpty() ? "" : "; ").append(line.toString().strip());
                    line.setLength(0);
                }
                if (exited) {
                    break;
                }
                if (!process.isAlive()) {
                    exited = true;
                    break;
                }
                // Sleeping, not spinning. This was 'Thread.onSpinWait()', which burns a core for
                // as long as the agent takes to answer - up to the whole timeout for one that
                // hangs, on every launch and for every agent asked. A millisecond costs nothing
                // against a process that is starting and bounds the waste at a thousand wakeups.
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    exited = true;
                    break;
                }
            }
        } catch (IOException ex) {
            throw new AgentException("Cannot read from " + executable, ex);
        } finally {
            if (!draining) {
                try {
                    reader.close();
                } catch (IOException ex) {
                    // Closing the pipe of a process being given up on.
                }
            }
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

    /**
     * Runs a step that talks to the agent within the limit a start has: the client waits without one, and an agent
     * that accepted the connection and never answered held every task start.
     */
    private <T> T bounded(java.util.function.Supplier<T> step) {
        try {
            return bounded(executable, step, READY_TIMEOUT_SECONDS);
        } catch (AgentException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains(" within ")) {
                throw new AgentException(ex.getMessage() + diagnosis(process.isAlive(), afterReady.toString()));
            }
            throw ex;
        }
    }

    /**
     * Runs a step that talks to an agent within a limit, on a thread of its own that ends with it.
     * <p>
     * A platform thread, not a virtual one: on a virtual thread the one step of a lookup that waits on the agent waited
     * on the scheduler too, and on a machine of two processors under load an agent that answered in a tenth of a
     * second was given up on. Closed after each use, and interrupted at the limit, so a step that never ends leaves
     * no thread behind.
     *
     * @param executable The agent, for the message.
     * @param step What to run.
     * @param seconds The limit.
     * @return What the step returned.
     */
    static <T> T bounded(Path executable, java.util.function.Supplier<T> step, long seconds) {
        final java.util.concurrent.ExecutorService runner = java.util.concurrent.Executors.newSingleThreadExecutor(
                task -> {
                    final Thread thread = new Thread(task, "sokar-agent-describe");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            return runner.submit(step::get).get(seconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new AgentException(executable + " did not describe itself within " + seconds
                    + " seconds");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted while asking " + executable, ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException failed ? failed
                    : new AgentException(executable + " failed to describe itself", ex);
        } finally {
            runner.shutdownNow();
        }
    }

    /**
     * Says what is known of an agent that did not answer in time: whether it still runs, and what it wrote after it
     * said it was ready - what tells a slow agent from a stuck one from a dead one.
     *
     * @param alive Whether its process still runs.
     * @param said What it wrote since its ready line.
     * @return The words to add to the refusal.
     */
    static String diagnosis(boolean alive, String said) {
        final String last = said.strip();
        return (alive ? " (still running" : " (it had exited") + "; since it was ready it wrote "
                + (last.isEmpty() ? "nothing" : "'" + last.replaceAll("\\p{Cntrl}+", " ") + "'") + ")";
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

    /**
     * Asks the agent to format lines of its output in place.
     *
     * @param lines Lines of its raw output.
     * @return Each line as the agent shows it, or {@code null} for a line it hides; the lines as they are from an
     *         agent that does not format them.
     */
    public List<@Nullable String> format(List<String> lines) {
        try {
            final Map<String, Object> reply = client.call(AgentProtocol.FORMAT, Map.of("lines", lines));
            if (reply.get("lines") instanceof List<?> shown && shown.size() == lines.size()) {
                final List<@Nullable String> result = new ArrayList<>();
                shown.forEach(line -> result.add(line instanceof String text ? text : null));
                return result;
            }
        } catch (org.fuin.sokar.wire.varlink.VarlinkException ex) {
            // An agent built before the method shows its lines as they are.
        }
        return new ArrayList<>(lines);
    }

    /**
     * Asks the agent whether a line of its output is the end of its run.
     *
     * @param line One line of its raw output.
     * @return How the run ended, or {@code null} for a line that is not its end, or for an agent that does not say.
     */
    public @Nullable AgentEnd ended(String line) {
        try {
            final Map<String, Object> reply = client.call(AgentProtocol.ENDED, Map.of("line", line));
            return reply.get("ended") instanceof Map<?, ?> ended ? AgentEnd.of(ended) : null;
        } catch (org.fuin.sokar.wire.varlink.VarlinkException ex) {
            // An agent built before the method: it says nothing about its end, as before.
            return null;
        }
    }

    private static Map<String, String> attributes(@Nullable Object value) {
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
