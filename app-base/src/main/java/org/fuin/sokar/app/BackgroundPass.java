package org.fuin.sokar.app;

/**
 * Starts one of the daemon's long-lived background passes - following projects, measuring upstreams, moving mail.
 * <p>
 * <strong>On a platform thread of its own, never a virtual one.</strong> Virtual threads share a few carrier threads -
 * as many as the machine has CPUs - with every call on the daemon's socket. A pass that blocks in a native call pins
 * its carrier, and on a two-CPU machine two such passes leave no carrier at all: the daemon then answered no call
 * for four minutes, with nothing in its log. A pass lives as long as the daemon and there are four of them, so a
 * thread each costs nothing; what it buys is that the socket's calls always have their carriers.
 */
final class BackgroundPass {

    private BackgroundPass() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Starts a pass.
     *
     * @param name The thread's name, which a thread dump shows.
     * @param pass What it does.
     * @return The thread.
     */
    static Thread start(final String name, final Runnable pass) {
        return Thread.ofPlatform().daemon().name(name).start(pass);
    }
}
