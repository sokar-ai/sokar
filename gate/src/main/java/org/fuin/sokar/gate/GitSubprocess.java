package org.fuin.sokar.gate;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@code git} for one smart-HTTP request, in bytes.
 * <p>
 * Deliberately not routed through {@code CommandRunner}: that seam works in strings, and the pack
 * protocol is binary. Decoding a pack as text and re-encoding it corrupts it - the symptom is
 * {@code inflate: data stream error} from the receiving end, which says nothing about the cause.
 * Anything carrying pack data has to stay in bytes from end to end.
 */
public class GitSubprocess implements GitHttpServer.GitProcess {

    private final String executable;

    private final long timeoutSeconds;

    /**
     * Constructor using the {@code git} on the path and a five-minute timeout.
     */
    public GitSubprocess() {
        this("git", 300);
    }

    /**
     * Constructor.
     *
     * @param executable Program name or path.
     * @param timeoutSeconds How long a single request may take.
     */
    public GitSubprocess(String executable, long timeoutSeconds) {
        this.executable = executable;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public void stream(List<String> arguments, java.nio.file.Path input, OutputStream output) throws IOException {

        final List<String> command = new ArrayList<>(List.of(executable));
        command.addAll(arguments);

        // Read from the file and written as it comes: a push or a fetch is held by neither side in memory whole.
        final Process process = new ProcessBuilder(command)
                .redirectInput(input.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        // The limit runs from the start, on a thread of its own: a git that hangs is ended, and the copy below with it.
        final Thread watch = Thread.ofVirtual().start(() -> {
            try {
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ex) {
                process.destroyForcibly();
            }
        });
        try (java.io.InputStream in = process.getInputStream()) {
            in.transferTo(output);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("git " + arguments.getFirst() + " timed out after " + timeoutSeconds + " s");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", ex);
        } finally {
            process.destroyForcibly();
            watch.interrupt();
        }
    }

    @Override
    public byte[] run(List<String> arguments, byte[] input) throws IOException {

        final List<String> command = new ArrayList<>(List.of(executable));
        command.addAll(arguments);

        final Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

        // Written on a separate thread: receive-pack answers while it is still reading, so writing
        // the whole request first can fill both pipe buffers and deadlock.
        final Thread writer = Thread.ofVirtual().start(() -> {
            try (OutputStream out = process.getOutputStream()) {
                out.write(input);
            } catch (IOException ex) {
                // git closed its input early, which it is entitled to do.
            }
        });

        // Read on a thread of its own, so the limit runs from the start: read here first, it began only once git
        // had closed its output, and a git that hung held its request for ever.
        final java.util.concurrent.CompletableFuture<byte[]> output = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().start(() -> {
            try {
                output.complete(process.getInputStream().readAllBytes());
            } catch (IOException ex) {
                output.completeExceptionally(ex);
            }
        });
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                throw new IOException("git " + arguments.getFirst() + " timed out after " + timeoutSeconds + " s");
            }
            writer.join();
            return output.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", ex);
        } catch (java.util.concurrent.ExecutionException ex) {
            throw new IOException("git " + arguments.getFirst() + " could not be read: " + ex.getCause(), ex);
        } catch (java.util.concurrent.TimeoutException ex) {
            throw new IOException("git " + arguments.getFirst() + " timed out after " + timeoutSeconds + " s", ex);
        } finally {
            // Whatever ended the request, git does not outlive it.
            process.destroyForcibly();
        }
    }
}
