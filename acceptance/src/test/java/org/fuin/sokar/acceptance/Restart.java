package org.fuin.sokar.acceptance;

import java.io.IOException;

/**
 * How the machine under test gets restarted.
 * <p>
 * <strong>Because a restart is a real state, not an edge case.</strong> A task's sockets live
 * under {@code $XDG_RUNTIME_DIR}, which the system clears when the user's last session ends, so a
 * reboot is when Sokar has to say what it can no longer do. Two defects found by hand on
 * 2026-09-09 were only visible on the far side of one, and one of them was in the very code
 * written to survive it.
 * <p>
 * <strong>An interface because the machines differ, not because the restart does.</strong> A local
 * VM and a rented server are reached the same way and reboot the same way; what differs is what to
 * do when one does not come back. That is the case a provider's API answers and ssh cannot, and it
 * is the reason this is an interface rather than one method.
 */
interface Restart {

    /**
     * Restarts the machine and returns once it answers again.
     *
     * @throws IOException If it cannot be restarted, or never comes back.
     */
    void restart() throws IOException;

    /** @return What this will do, for a scenario that reports what it did. */
    String describe();
}
