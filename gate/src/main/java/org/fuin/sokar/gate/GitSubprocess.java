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

        final byte[] output = process.getInputStream().readAllBytes();

        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("git " + arguments.getFirst() + " timed out");
            }
            writer.join();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git", ex);
        }
        return output;
    }
}
