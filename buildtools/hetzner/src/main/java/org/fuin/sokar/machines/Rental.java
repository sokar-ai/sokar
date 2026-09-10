package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * A machine that has been rented, and is deleted when this is closed.
 * <p>
 * <strong>The delete is not conditional on success.</strong> The expensive mistake is a server
 * that outlives a script which failed on line three, not one that is deleted twice.
 */
public final class Rental implements AutoCloseable {

    /** How long to wait for sshd after the API says the server is running. */
    private static final Duration SSH_PATIENCE = Duration.ofMinutes(5);

    private final Hetzner hetzner;

    private final long id;

    private final Spec spec;

    private final String address;

    private Ssh ssh;

    Rental(Hetzner hetzner, long id, Spec spec, String address) {
        this.hetzner = hetzner;
        this.id = id;
        this.spec = spec;
        this.address = address;
    }

    /**
     * Returns where the machine is.
     *
     * @return Its address.
     */
    public String address() {
        return address;
    }

    /**
     * Waits until the machine answers ssh and accepts the key.
     *
     * @throws IOException If it never does.
     */
    public void awaitSsh() throws IOException {
        final Instant deadline = Instant.now().plus(SSH_PATIENCE);
        while (!Ssh.reachable(address, spec.user(), spec.credential())) {
            if (Instant.now().isAfter(deadline)) {
                throw new IOException(address + " did not accept ssh within "
                        + SSH_PATIENCE.toMinutes() + " minutes");
            }
            try {
                Thread.sleep(Duration.ofSeconds(3).toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted waiting for ssh", ex);
            }
        }
    }

    /**
     * Returns the connection to the machine, opening it the first time it is asked for.
     *
     * @return The connection.
     * @throws IOException If it cannot be opened.
     */
    public Ssh ssh() throws IOException {
        if (ssh == null) {
            ssh = Ssh.to(address, spec.user(), spec.credential());
        }
        return ssh;
    }

    @Override
    public void close() throws IOException {
        try {
            if (ssh != null) {
                ssh.close();
            }
        } catch (IOException ex) {
            // A connection that will not close is not a reason to leave a server billing.
            System.out.println("could not close the connection: " + ex.getMessage());
        }
        if (spec.keep()) {
            System.out.println("KEEPING " + spec.name() + " at " + address
                    + " - it is costing money until it is swept or deleted by hand.");
            return;
        }
        System.out.println("deleting " + spec.name());
        try {
            hetzner.delete(id);
            System.out.println("deleted  " + spec.name());
        } catch (IOException ex) {
            System.err.println("COULD NOT DELETE " + spec.name() + ": " + ex.getMessage());
            System.err.println("  delete it by hand, it is billing: " + address);
            throw ex;
        }
    }
}
