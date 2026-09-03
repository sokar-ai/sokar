package org.fuin.sokar.hooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.wire.Sidecar;

/**
 * Starts the reader that turns dropped packets into an audit trail.
 * <p>
 * The reader itself cannot live in this binary: reading NFLOG needs the Foreign Function &amp;
 * Memory API, and a hook that used FFM could not be linked statically ({@code S5}). So the hook
 * starts the {@code sokar} binary instead, inside the container's network namespace, and that
 * process does the work.
 * <p>
 * Events are written to a file rather than sent anywhere. Nothing may need to be listening yet -
 * the clearance watcher is started after the container is up, and may be stopped and restarted
 * while a task runs. A file means the audit trail exists either way, which is the point of an
 * audit trail.
 * <p>
 * <strong>Soft-fail.</strong> Losing this costs visibility, not containment: the firewall is
 * already loaded by the time this runs and keeps dropping whether anyone is reading or not.
 */
public class ReaderHook extends Hook {

    /** File the reader appends events to, as one JSON object per line. */
    public static final String EVENTS_FILE = org.fuin.sokar.wire.ReaderEvents.FILE;

    /** File the reader's process id is written to, so poststop can reap it. */
    static final String PID_FILE = "reader.pid";

    /**
     * Constructor.
     */
    public ReaderHook() {
        super("reader", false);
    }

    @Override
    protected void run(String stage, OciState state, Sidecar sidecar) throws Exception {
        if ("poststop".equals(stage)) {
            return;
        }
        startReader(state, sidecar);
    }

    private void startReader(OciState state, Sidecar sidecar) throws IOException {

        if (state.pid() <= 0) {
            throw new IOException("The runtime reported no container process");
        }
        final Path sokar = Path.of(sidecar.sokarBinary());
        if (!Files.isExecutable(sokar)) {
            throw new IOException("No sokar binary at " + sokar);
        }

        final Path stateDirectory = Path.of(sidecar.stateDirectory());
        Files.createDirectories(stateDirectory);

        final List<String> command = List.of("nsenter", "--target", String.valueOf(state.pid()),
                "--net", sokar.toString(), "shield", "read");

        final Process reader = new ProcessBuilder(command)
                .redirectErrorStream(false)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        stateDirectory.resolve(EVENTS_FILE).toFile()))
                .redirectError(ProcessBuilder.Redirect.appendTo(
                        stateDirectory.resolve("reader.err").toFile()))
                .start();

        Files.writeString(stateDirectory.resolve(PID_FILE), String.valueOf(reader.pid()),
                StandardCharsets.UTF_8);

        // Long enough to catch a reader that cannot bind at all, which is the common failure and
        // is silent otherwise.
        try {
            if (reader.waitFor(500, TimeUnit.MILLISECONDS)) {
                throw new IOException("The reader exited immediately with " + reader.exitValue()
                        + "; see " + stateDirectory.resolve("reader.err"));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        log(sidecar, "createRuntime", "reader started, pid " + reader.pid());
    }

    /**
     * Entry point of the {@code sokar-hook-reader} binary.
     *
     * @param args Command line arguments; the first is the stage name.
     */
    public static void main(String[] args) {
        System.exit(new ReaderHook().execute(args, System.in, System.err));
    }
}
