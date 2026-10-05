package org.fuin.sokar.hooks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.wire.Sidecar;

/**
 * Starts the per-container services, and reaps them once the container is gone.
 * <p>
 * Today that means the resolver. A task container's {@code resolv.conf} points at its own
 * loopback, so without something listening there the agent cannot resolve anything at all - and
 * the failure looks like a network problem rather than a missing process.
 * <p>
 * <strong>Soft-fail.</strong> A container without a resolver is degraded, and visibly so at the
 * point of use. Refusing to start it would turn a degraded run into no run, which is the wrong
 * trade for something that is not a containment property.
 */
public class SupervisorHook extends Hook {

    /** File the resolver's process id is written to, so poststop can reap it. */
    static final String PID_FILE = "dnsmasq.pid";

    /** File the resolver's query log is written to. */
    static final String LOG_FILE = "dnsmasq.log";

    /**
     * Constructor.
     */
    public SupervisorHook() {
        super("supervisor", false);
    }

    @Override
    protected void run(String stage, OciState state, Sidecar sidecar) throws Exception {
        if ("poststop".equals(stage)) {
            reap(sidecar);
        } else {
            startResolver(state, sidecar);
        }
    }

    private void startResolver(OciState state, Sidecar sidecar) throws IOException {

        final Path config = Path.of(sidecar.dnsConfigFile());
        if (!Files.isRegularFile(config)) {
            throw new IOException("No resolver configuration at " + config);
        }
        if (state.pid() <= 0) {
            throw new IOException("The runtime reported no container process");
        }

        final Path state_ = Path.of(sidecar.stateDirectory());
        Files.createDirectories(state_);

        // nsenter for the same reason the nft hook uses it: setns is per-thread and this process
        // must not join the container's network namespace itself.
        final List<String> command = List.of("nsenter", "--target", String.valueOf(state.pid()),
                "--net", "dnsmasq", "--keep-in-foreground", "--conf-file=" + config);

        final Process resolver = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(state_.resolve(LOG_FILE).toFile())
                .start();

        // Deliberately not waited for: it has to outlive this hook. A child survives its parent,
        // and poststop reaps it from the recorded pid.
        // With its start time, as every helper's record is: poststop verifies both before it signals,
        // and a bare pid is refused - which left this process, and conmon above it, running after
        // every task was removed.
        org.fuin.sokar.wire.HelperPid.record(state_.resolve(PID_FILE), resolver.toHandle());

        // Give it long enough to fail loudly. A configuration error kills dnsmasq immediately, and
        // reporting that here beats a container that resolves nothing for reasons nobody logged.
        try {
            if (resolver.waitFor(500, TimeUnit.MILLISECONDS)) {
                throw new IOException("The resolver exited immediately with "
                        + resolver.exitValue() + "; see " + state_.resolve(LOG_FILE));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        log(sidecar, "createRuntime", "resolver started, pid " + resolver.pid());
    }

    /**
     * Stops every helper that left a pid file behind.
     * <p>
     * Not just this hook's own: the reader hook starts one, and {@code task run} starts the
     * clearance watcher. Each writes a {@code *.pid} file into the container's state directory,
     * and this is the one place that runs when the container is definitely gone. Reaping by
     * convention rather than by a list means a helper added later is cleaned up without anyone
     * remembering to come back here.
     */
    private void reap(Sidecar sidecar) {
        final Path stateDirectory = Path.of(sidecar.stateDirectory());
        try (var files = Files.list(stateDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".pid"))
                    .forEach(path -> reapOne(sidecar, path));
        } catch (IOException | RuntimeException ex) {
            // The container is already gone. Anything that outlives it is tidied when the runtime
            // directory is cleared at logout, and failing here helps nobody.
            log(sidecar, "poststop", "could not list the state directory: " + ex);
        }
    }

    private void reapOne(Sidecar sidecar, Path pidFile) {
        final String name = pidFile.getFileName().toString().replace(".pid", "");
        try {
            // Verified against the recorded start time, not just parsed. An id is reused, and a
            // file that outlived its helper otherwise names whatever holds that id now.
            org.fuin.sokar.wire.HelperPid.verified(pidFile).ifPresentOrElse(handle -> {
                handle.destroy();
                log(sidecar, "poststop", name + " " + handle.pid() + " stopped");
            }, () -> log(sidecar, "poststop",
                    name + " was gone, or its record no longer names it; nothing was signalled"));
            Files.deleteIfExists(pidFile);
        } catch (IOException | RuntimeException ex) {
            log(sidecar, "poststop", "could not reap " + name + ": " + ex);
        }
    }

    /**
     * Entry point of the {@code sokar-hook-supervisor} binary.
     *
     * @param args Command line arguments; the first is the stage name.
     */
    public static void main(String[] args) {
        System.exit(new SupervisorHook().execute(args, System.in, System.err));
    }
}
