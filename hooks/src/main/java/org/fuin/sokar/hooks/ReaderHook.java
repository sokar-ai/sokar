package org.fuin.sokar.hooks;

import org.fuin.sokar.wire.Sidecar;

/**
 * Starts the NFLOG reader that turns dropped packets into audit events.
 * <p>
 * <strong>Soft-fail.</strong> Losing the audit trail costs visibility, not containment: the
 * firewall is already loaded by the time this runs, and it keeps dropping whether anyone is
 * listening or not.
 */
public class ReaderHook extends Hook {

    /**
     * Constructor.
     */
    public ReaderHook() {
        super("reader", false);
    }

    @Override
    protected void run(String stage, OciState state, Sidecar sidecar) throws Exception {
        log(sidecar, "createRuntime", "reader not implemented yet");
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
