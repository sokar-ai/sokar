package org.fuin.sokar.machines;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/**
 * A machine held for as long as this is open.
 * <p>
 * <strong>Releasing is not conditional on success.</strong> The expensive mistake is a machine
 * that outlives a script which failed on line three, not one that is released twice. What
 * releasing means is the {@link Machines} implementation's business: a rented server is destroyed,
 * a local virtual machine is left running because starting it again costs forty seconds and
 * nothing else.
 */
public final class Lease implements AutoCloseable {

    /** How long to wait for sshd after the machine is said to be running. */
    private static final Duration SSH_PATIENCE = Duration.ofMinutes(5);

    /** How long between attempts at a machine that is still booting. */
    private static final Duration SSH_INTERVAL = Duration.ofSeconds(3);

    /** What giving the machine back means, which differs by where it came from. */
    @FunctionalInterface
    public interface Release {

        /**
         * Gives the machine back.
         *
         * @throws IOException If it could not be given back, which is worth being loud about.
         */
        void release() throws IOException;
    }

    private final long id;

    private final String name;

    private final String address;

    private final Spec spec;

    private final Release release;

    private Ssh ssh;

    /**
     * Constructor with everything a holder needs.
     *
     * @param id The provider's id for it, or {@code 0} where the provider has none - a machine
     *     already on this host is named, not numbered.
     * @param name What the machine is called, for messages.
     * @param address Where it is.
     * @param spec What was asked for.
     * @param release What giving it back means.
     */
    public Lease(long id, String name, String address, Spec spec, Release release) {
        this.id = id;
        this.name = name;
        this.address = address;
        this.spec = spec;
        this.release = release;
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
     * Returns the provider's id for this machine.
     *
     * @return The id, or {@code 0} where the provider numbers nothing.
     */
    public long id() {
        return id;
    }

    /**
     * Returns what the machine is called.
     *
     * @return Its name.
     */
    public String name() {
        return name;
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
            sleep(SSH_INTERVAL);
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

    /**
     * Leaves exactly one Sokar agent installed, whatever was there before.
     * <p>
     * <strong>Named rather than assumed.</strong> A machine keeps whatever the last run put on it,
     * so a suite that needs one agent and finds another fails with "no agent called 'x' is
     * installed" - which reads like a packaging fault rather than a machine that was never
     * prepared. Removing first is what makes the run say what it needs instead of inheriting it.
     *
     * @param agent Agent package to leave installed, such as {@code sokar-agent-pi}.
     * @throws IOException If the machine cannot be reached or the package is not there.
     */
    public void onlyAgent(String agent) throws IOException {
        final Ssh open = ssh();
        final Ssh.Output removed = open.run(REMOVE_AGENTS);
        if (removed.status() != 0) {
            throw new IOException("could not remove the agents on " + name + ": " + removed.all());
        }
        final Ssh.Output installed = open.run(install(agent));
        if (installed.status() != 0) {
            throw new IOException("could not install " + agent + " on " + name + ": "
                    + installed.all());
        }
    }

    /** Removes every Sokar agent, on either package manager, without failing when there is none. */
    private static final String REMOVE_AGENTS =
            "set -e; if command -v apt-get >/dev/null; then "
            + "p=$(dpkg-query -W -f='${Package}\\n' 'sokar-agent-*' 2>/dev/null || true); "
            + "[ -z \"$p\" ] || sudo apt-get remove -y -qq $p >/dev/null; "
            + "else p=$(rpm -qa 'sokar-agent-*'); "
            + "[ -z \"$p\" ] || sudo dnf remove -y -q $p >/dev/null; fi";

    private static String install(String agent) {
        return "set -e; if command -v apt-get >/dev/null; then sudo apt-get update -qq >/dev/null; "
                + "sudo apt-get install -y -qq " + agent + " >/dev/null; "
                + "else sudo dnf install -y -q " + agent + " >/dev/null; fi";
    }

    @Override
    public void close() throws IOException {
        try {
            if (ssh != null) {
                ssh.close();
            }
        } catch (IOException ex) {
            // A connection that will not close is not a reason to leave a machine held.
            System.out.println("could not close the connection to " + name + ": "
                    + ex.getMessage());
        }
        release.release();
    }

    private void sleep(Duration duration) throws IOException {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + name, ex);
        }
    }
}
