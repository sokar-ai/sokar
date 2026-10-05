package org.fuin.sokar.app;

import java.nio.file.Path;
import org.fuin.sokar.gate.GitGate;

/**
 * The {@code git fetch} that brings one waiting push into a person's own clone, over the ssh they already have.
 * <p>
 * Reading, never running: the fetch changes nothing on this machine, and the work it brings is the agent's, not yet
 * reviewed. Building it or running its tests outside a container runs that work with the person's rights, which is
 * why {@link #ADVICE} is said beside it.
 */
public final class WaitingFetch {

    /** Said once beside the fetches. */
    public static final String ADVICE = "read it in your own clone: run the fetch there and open it in your IDE's"
            + " safe mode. Do not build it or run its tests on your computer - it is the agent's work, not reviewed.";

    private WaitingFetch() {
    }

    /**
     * Returns the fetch for one waiting push.
     *
     * @param mirror The bare mirror it waits in.
     * @param name The waiting ref, without its namespace.
     * @return One line a person can paste into a clone of the same repository.
     */
    public static String of(final Path mirror, final String name) {
        return "git fetch ssh://" + System.getProperty("user.name") + "@" + host() + mirror.toAbsolutePath()
                + " " + GitGate.INCOMING + name + ":refs/remotes/sokar/" + name;
    }

    private static String host() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (final java.net.UnknownHostException ex) {
            // Said as what it stands for rather than guessed: the person knows the name they reach this machine by.
            return "<this machine>";
        }
    }
}
