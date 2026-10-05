package org.fuin.sokar.app;

/**
 * Runs a cleanup once, whether the command returns or the terminal dies under it.
 * <p>
 * <strong>Why this exists.</strong> An attached {@code task run} cleans up in a {@code finally},
 * which covers every way the command can end except the one that actually happened: somebody
 * pressed Ctrl-C, or closed the terminal. The signal goes to the whole foreground process group,
 * so the gate, the credential broker and the clearance watcher - plain children, not detached -
 * die with it, while the container, its ruleset and its resolver keep running. Measured on the
 * test machine: a container up eleven minutes with the agent still working inside it, no gate to
 * push through and no broker to reach the provider. That is not "kept", it is abandoned, and it
 * is the state {@code cleanUp} exists to prevent.
 * <p>
 * <strong>Why a lock rather than a flag.</strong> On a signal both the shutdown hook and the
 * main thread's {@code finally} try to tear down at once. A flag would let the hook see "somebody
 * else is doing it" and return - and the runtime waits for hook threads, not for the main thread,
 * so the process could exit with the cleanup half done. The hook has to <em>wait</em> for a
 * teardown already in progress, which is what a lock does and a flag does not.
 */
public final class Teardown implements AutoCloseable {

    private final Runnable action;

    private final Thread hook;

    private boolean done;

    private Teardown(Runnable action) {
        this.action = action;
        this.hook = new Thread(this::run, "sokar-teardown");
    }

    /**
     * Registers a cleanup to run at the latest when this process ends.
     *
     * @param action What to clean up. Runs at most once.
     * @return The registration, to be closed on the normal path.
     */
    public static Teardown arm(Runnable action) {
        final Teardown teardown = new Teardown(action);
        Runtime.getRuntime().addShutdownHook(teardown.hook);
        return teardown;
    }

    /**
     * Runs the cleanup if it has not run, and waits for it if another thread is running it.
     */
    public synchronized void run() {
        if (done) {
            return;
        }
        done = true;
        action.run();
    }

    /**
     * Runs the cleanup and takes the hook back off.
     */
    @Override
    public void close() {
        run();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException ex) {
            // Already shutting down, so the hook is on its way out anyway. Nothing to undo.
        }
    }
}
