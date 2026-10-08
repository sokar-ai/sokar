package org.fuin.sokar.build.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.fuin.sokar.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * Sokar's side of {@link BuildProtocol#INTERFACE}: one reader executable, started, handshaken, and asked.
 * <p>
 * A reader that will not start, speaks another protocol version, or reads another forge than the one asked for is
 * refused here, with its own name in the message, before anything is asked of it.
 */
public final class InstalledBuildReader implements AutoCloseable {

    /** Where Sokar's packages install a reader. */
    public static final Path PACKAGED = Path.of("/usr/libexec/sokar/builds");

    /** How long a reader may take to start and to describe itself. */
    static final long READY_SECONDS = 10;

    /** How long one question may take: a failing job's log is downloaded within it. */
    static final long CALL_SECONDS = 120;

    private final Path executable;

    private final Path socket;

    private final Process process;

    private final VarlinkClient client;

    private final String forge;

    /**
     * Returns the reader of a forge, from the first of the directories that holds one.
     *
     * @param forge The forge, as a project file names it.
     * @param directories Where to look, in order: the account's own first, then {@link #PACKAGED}.
     * @return The executable.
     * @throws BuildReaderException When none holds it, naming where it looked.
     */
    public static Path find(final String forge, final List<Path> directories) {
        if (!forge.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new BuildReaderException("'" + forge + "' is not a build reader's name");
        }
        for (final Path directory : directories) {
            final Path candidate = directory.resolve(forge);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        throw new BuildReaderException("No build reader for '" + forge + "' is installed; looked in " + directories
                + ". Install the package that reads builds from it.");
    }

    /**
     * Starts a reader and completes its handshake.
     *
     * @param executable The reader.
     * @param forge The forge it must read.
     * @param socket Where it creates its socket; owner-only, since every call carries the token.
     * @throws BuildReaderException When it does not start, speaks another protocol, or reads another forge.
     */
    public InstalledBuildReader(final Path executable, final String forge, final Path socket) {
        this.executable = executable;
        this.socket = socket;
        try {
            Files.deleteIfExists(socket);
            this.process = new ProcessBuilder(executable.toString(), "serve", socket.toString())
                    .redirectErrorStream(true).start();
        } catch (IOException ex) {
            throw new BuildReaderException("Cannot start " + executable + ": " + ex.getMessage(), ex);
        }
        VarlinkClient opened = null;
        try {
            awaitReady();
            opened = new VarlinkClient(socket);
            final VarlinkClient described = opened;
            final Map<String, Object> answer = bounded(() -> described.call(BuildProtocol.DESCRIBE, Map.of()),
                    READY_SECONDS);
            final int version = answer.get("protocolVersion") instanceof Number number ? number.intValue() : -1;
            if (version != BuildProtocol.VERSION) {
                throw new BuildReaderException(executable + " speaks " + BuildProtocol.INTERFACE + " version "
                        + version + "; this Sokar speaks version " + BuildProtocol.VERSION
                        + ". Install the reader's version that goes with this Sokar.");
            }
            this.forge = String.valueOf(answer.get("forge"));
            if (!forge.equals(this.forge)) {
                throw new BuildReaderException(executable + " reads '" + this.forge + "', not '" + forge + "'");
            }
            this.client = opened;
        } catch (RuntimeException ex) {
            process.destroyForcibly();
            closeQuietly(opened);
            throw ex instanceof BuildReaderException reader ? reader
                    : new BuildReaderException(executable + " failed to start: " + ex.getMessage(), ex);
        }
    }

    /**
     * Returns the forge the reader reads.
     *
     * @return Its name.
     */
    public String forge() {
        return forge;
    }

    /**
     * Asks where a branch points.
     *
     * @param target Where and with what.
     * @param branch The branch's name.
     * @return The full sha; "" when the forge has no such branch.
     * @throws BuildRefused When the forge would not answer.
     * @throws BuildReaderException When the reader failed.
     */
    public String head(final Target target, final String branch) {
        final Map<String, Object> parameters = parameters(target);
        parameters.put("branch", branch);
        final Object commit = call(BuildProtocol.HEAD, parameters).get("commit");
        return commit == null ? "" : String.valueOf(commit);
    }

    /**
     * Asks what the build of a commit did.
     *
     * @param target Where and with what.
     * @param commit The full sha.
     * @param logs Which jobs' logs to hand over.
     * @return The build.
     * @throws BuildRefused When the forge would not answer.
     * @throws BuildReaderException When the reader failed, or answered something that is not a build.
     */
    public Build look(final Target target, final String commit, final Build.Logs logs) {
        final Map<String, Object> parameters = parameters(target);
        parameters.put("commit", commit);
        parameters.put("logs", logs.wire());
        try {
            return Build.of(call(BuildProtocol.LOOK, parameters));
        } catch (IllegalArgumentException ex) {
            throw new BuildReaderException(executable + " answered something that is not a build: "
                    + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        closeQuietly(client);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        try {
            Files.deleteIfExists(socket);
        } catch (IOException ex) {
            // The socket is this reader's alone, in a directory that goes with the task.
        }
    }

    private static Map<String, Object> parameters(final Target target) {
        final Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("upstream", target.upstream());
        parameters.put("api", target.api());
        parameters.put("token", target.token());
        return parameters;
    }

    private Map<String, Object> call(final String method, final Map<String, Object> parameters) {
        try {
            return bounded(() -> client.call(method, parameters), CALL_SECONDS);
        } catch (VarlinkException ex) {
            final BuildRefused.Reason reason = BuildRefused.Reason.of(ex.getErrorName());
            final Map<String, Object> said = ex.getParameters() == null ? Map.of() : ex.getParameters();
            if (reason != null) {
                throw new BuildRefused(reason, String.valueOf(said.getOrDefault("detail", "")),
                        said.get("retryAfter") instanceof Number number ? number.longValue() : 0);
            }
            throw new BuildReaderException(executable + " failed: " + (ex.getErrorName() == null ? ex.getMessage()
                    : ex.getErrorName() + " " + said), ex);
        }
    }

    /** Waits for the ready line the reader prints once its socket listens; whatever it said instead is the reason. */
    private void awaitReady() {
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        final String said = bounded(() -> {
            final StringBuilder lines = new StringBuilder();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("ready ")) {
                        drain(reader);
                        return null;
                    }
                    lines.append(lines.isEmpty() ? "" : "; ").append(line.strip());
                }
            } catch (IOException ex) {
                lines.append(ex.getMessage());
            }
            return lines.toString();
        }, READY_SECONDS);
        if (said != null) {
            throw new BuildReaderException(executable + " exited without becoming ready"
                    + (said.isEmpty() ? "" : ", saying: " + said));
        }
    }

    /** Keeps reading what the reader writes after it is ready, so a line more never meets a full pipe. */
    private static void drain(final BufferedReader reader) {
        Thread.ofPlatform().daemon().name("sokar-build-reader-output").start(() -> {
            try (reader) {
                while (reader.readLine() != null) {
                    // Discarded: a reader's answers come over the socket.
                }
            } catch (IOException ex) {
                // The reader ended.
            }
        });
    }

    /** Runs a step against the reader within a limit: the varlink client waits without one. */
    private <T> T bounded(final Supplier<T> step, final long seconds) {
        final ExecutorService runner = Executors.newSingleThreadExecutor(task -> {
            final Thread thread = new Thread(task, "sokar-build-reader-call");
            thread.setDaemon(true);
            return thread;
        });
        try {
            return runner.submit(step::get).get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            process.destroyForcibly();
            throw new BuildReaderException(executable + " did not answer within " + seconds + " seconds", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new BuildReaderException(executable + " failed: " + ex.getCause(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BuildReaderException("Interrupted while asking " + executable, ex);
        } finally {
            runner.shutdownNow();
        }
    }

    private static void closeQuietly(final @Nullable VarlinkClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (IOException ex) {
                // Closing a connection to a reader that is being ended.
            }
        }
    }
}
