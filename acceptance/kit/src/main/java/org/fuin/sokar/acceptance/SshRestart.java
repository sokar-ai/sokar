package org.fuin.sokar.acceptance;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * Restarts the machine by telling it to reboot, over the connection already open.
 * <p>
 * <strong>Why this and not a provider API.</strong> A reboot needs no API key: a machine this
 * suite can already reach can already be told to reboot, and the credential for that is the ssh
 * key it is holding. Adding a Hetzner token would put a second secret into CI - one that can
 * create and destroy servers - to do something the first one already does. The API belongs to the
 * case ssh cannot answer: a machine that does not come back, which needs a power cycle rather
 * than a request. That implementation can be added beside this one when something needs it.
 * <p>
 * <strong>Coming back is the hard half.</strong> The reboot itself kills the connection, so the
 * command never returns cleanly and its failure means nothing. What is waited for is the machine
 * answering again - and first for it to stop answering, because a machine that has not gone down
 * yet still answers, and a suite that skipped that would carry on against the old boot and prove
 * nothing.
 */
final class SshRestart implements Restart {

    /** How long to wait for the machine to go away before deciding it never will. */
    private static final Duration LEAVING = Duration.ofSeconds(60);

    /** How long to wait for it to come back. */
    private static final Duration RETURNING = Duration.ofMinutes(5);

    private final Machine machine;

    SshRestart(Machine machine) {
        this.machine = machine;
    }

    @Override
    public void restart() throws IOException {
        try {
            // Detached and delayed, so sshd is not killed before the request is acknowledged -
            // otherwise the reboot races the reply and it is unclear whether it was asked for.
            machine.run("sudo systemd-run --on-active=1s /sbin/reboot || sudo reboot &");
        } catch (IOException | RuntimeException ex) {
            // The connection dying IS the reboot starting. A failure here says nothing.
        }
        machine.disconnect();
        awaitGone();
        awaitBack();
    }

    private void awaitGone() throws IOException {
        final Instant deadline = Instant.now().plus(LEAVING);
        while (Instant.now().isBefore(deadline)) {
            if (!machine.reachable()) {
                return;
            }
            sleep(Duration.ofSeconds(2));
        }
        throw new IOException("The machine never went down: " + describe());
    }

    private void awaitBack() throws IOException {
        final Instant deadline = Instant.now().plus(RETURNING);
        while (Instant.now().isBefore(deadline)) {
            if (machine.reachable()) {
                machine.reconnect();
                return;
            }
            sleep(Duration.ofSeconds(3));
        }
        throw new IOException("The machine did not come back within " + RETURNING.toMinutes()
                + " minutes. A power cycle needs the provider's API, which this does not have.");
    }

    private static void sleep(Duration how) {
        try {
            Thread.sleep(how.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public String describe() {
        return "reboot over ssh";
    }
}
