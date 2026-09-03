package org.fuin.sokar.hooks;

import org.fuin.sokar.wire.Sidecar;

/**
 * Starts the per-container supervisor, and reaps it once the container is gone.
 * <p>
 * <strong>Soft-fail.</strong> Without a supervisor the task loses the vault broker, the SSH signer
 * and the git gate, and will fail when it tries to use them - visibly, at the point of use.
 * Refusing to start the container instead would turn a degraded run into no run at all.
 */
public class SupervisorHook extends Hook {

    /**
     * Constructor.
     */
    public SupervisorHook() {
        super("supervisor", false);
    }

    @Override
    protected void run(OciState state, Sidecar sidecar) throws Exception {
        // The supervisor itself arrives with the vault and the git gate. Until then the hook is
        // installed and gated correctly, which is what the container lifecycle depends on.
        log(sidecar, "createRuntime", "supervisor not implemented yet");
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
