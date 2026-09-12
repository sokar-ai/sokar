package org.fuin.sokar.core.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Runs external programs with {@link ProcessBuilder}.
 * <p>
 * Output is drained on virtual threads. A program that fills a pipe buffer while nobody reads it
 * blocks forever, and {@code podman build} on a large base image fills one easily.
 */
public class ProcessCommandRunner implements CommandRunner {

    private final Duration timeout;

    /**
     * Constructor with a default timeout of ten minutes.
     */
    public ProcessCommandRunner() {
        this(Duration.ofMinutes(10));
    }

    /**
     * Constructor with a timeout.
     *
     * @param timeout How long a program may run before it is killed.
     */
    public ProcessCommandRunner(Duration timeout) {
        this.timeout = timeout;
    }

    @Override
    public CommandResult run(Command command) {

        final ProcessBuilder builder = new ProcessBuilder(command.arguments());
        if (command.workingDirectory() != null) {
            builder.directory(command.workingDirectory().toFile());
        }
        builder.environment().putAll(command.environment());

        try {

            final Process process = builder.start();

            final Drain out = new Drain(process.getInputStream());
            final Drain err = new Drain(process.getErrorStream());
            final Thread outThread = Thread.ofVirtual().start(out);
            final Thread errThread = Thread.ofVirtual().start(err);

            writeInput(process, command);

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                outThread.join();
                errThread.join();
                throw new CommandException(command,
                        new IOException("Timed out after " + timeout.toSeconds() + " s"));
            }
            outThread.join();
            errThread.join();

            return new CommandResult(command, process.exitValue(), out.text(), err.text());

        } catch (IOException ex) {
            throw new CommandException(command, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CommandException(command, ex);
        }
    }

    private void writeInput(Process process, Command command) throws IOException {
        try (OutputStream in = process.getOutputStream()) {
            if (command.input() != null) {
                in.write(command.input().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Reads one stream to the end, keeping whatever it managed to read if the stream breaks.
     */
    private static final class Drain implements Runnable {

        private final InputStream stream;

        private final java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();

        private volatile String text = "";

        private Drain(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (stream) {
                final byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    collected.write(buffer, 0, read);
                }
            } catch (IOException ex) {
                // The process is gone. Whatever was read before that is still the best diagnostic
                // available, so this is not turned into a failure of its own.
                //
                // It used to say exactly that and then throw the bytes away: 'readAllBytes' either
                // returns everything or throws, and the catch set the result to the empty string.
                // So a command that died mid-output was reported with no output at all - the one
                // case where the output is worth most. Reading incrementally is what makes the
                // comment above true.
            } finally {
                text = collected.toString(StandardCharsets.UTF_8);
            }
        }

        private String text() {
            return text;
        }
    }
}
