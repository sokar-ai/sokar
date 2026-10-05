package org.fuin.sokar.acceptance;

import java.io.IOException;
import org.fuin.sokar.machines.Ssh;
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
        String asked = "the command produced nothing before the connection went";
        try {
            // Detached and delayed, so sshd is not killed before the request is acknowledged -
            // otherwise the reboot races the reply and it is unclear whether it was asked for.
            final Ssh.Output output = machine.run(
                    "sudo systemd-run --on-active=1s /sbin/reboot || { sudo reboot & }");
            asked = "exit " + output.status() + "; " + output.all().strip();
        } catch (IOException | RuntimeException ex) {
            // The connection dying IS the reboot starting, so this is not an error by itself -
            // but it is the only account of what happened, and it used to be discarded. When the
            // machine then did not go down, the failure said "reboot over ssh" and nothing about
            // why: CI on 2026-09-12, where the reboot never landed on either leg.
            asked = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
        machine.disconnect();
        try {
            awaitGone();
            awaitBack();
        } catch (IOException ex) {
            // Reconnect before giving up. Without this one failed restart left the connection
            // down and EVERY scenario after it failed with "Not connected" - 28 errors for one
            // cause, in a report where the one that mattered was indistinguishable from the
            // wreckage. A scenario may fail; it may not take the rest of the run with it.
            reconnectQuietly();
            throw new IOException(ex.getMessage() + ". What the reboot request answered: " + asked,
                    ex);
        }
    }

    /**
     * Restores the connection after a restart that did not happen, so the next scenario runs.
     */
    private void reconnectQuietly() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (machine.reachable()) {
                    machine.reconnect();
                    return;
                }
            } catch (IOException | RuntimeException ex) {
                // Nothing to add: the caller is already throwing the reason this was needed.
            }
            sleep(Duration.ofSeconds(2));
        }
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
