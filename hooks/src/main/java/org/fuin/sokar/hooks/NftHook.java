package org.fuin.sokar.hooks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.fuin.sokar.wire.Sidecar;

/**
 * Loads the generated nftables ruleset into the container's network namespace.
 * <p>
 * <strong>Fail-closed.</strong> If anything here goes wrong the container must not start: a task
 * container that comes up without its egress policy is exactly the situation Sokar exists to
 * prevent, and it would look completely normal to the operator.
 * <p>
 * Runs at {@code createRuntime}, where the runtime has created the namespaces but has not yet
 * pivoted into the container's root filesystem. The ruleset was generated on the host before the
 * container was created, so this hook makes no policy decision of its own.
 */
public class NftHook extends Hook {

    /**
     * Constructor.
     */
    public NftHook() {
        super("nft", true);
    }

    @Override
    protected void run(OciState state, Sidecar sidecar) throws Exception {

        final Path ruleset = Path.of(sidecar.rulesetFile());
        if (!Files.isRegularFile(ruleset)) {
            throw new IOException("No ruleset at " + ruleset);
        }
        if (state.pid() <= 0) {
            throw new IOException("The runtime reported no container process");
        }

        // nsenter is used rather than a Java thread entering the namespace: setns() is per-thread
        // and the JVM offers no control over which thread anything runs on. A child process is the
        // only way to be sure the ruleset lands in the container's namespace and not the host's.
        final List<String> command = List.of("nsenter", "--target", String.valueOf(state.pid()),
                "--net", "nft", "--file", ruleset.toString());

        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();

        final String output = new String(process.getInputStream().readAllBytes());

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Loading the ruleset timed out");
        }
        if (process.exitValue() != 0) {
            throw new IOException("nft failed with exit code " + process.exitValue()
                    + (output.isBlank() ? "" : ": " + output.strip()));
        }
    }

    /**
     * Entry point of the {@code sokar-hook-nft} binary.
     *
     * @param args Command line arguments; the first is the stage name.
     */
    public static void main(String[] args) {
        System.exit(new NftHook().execute(args, System.in, System.err));
    }
}
