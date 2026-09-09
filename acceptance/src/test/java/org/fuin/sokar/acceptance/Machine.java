package org.fuin.sokar.acceptance;

import java.io.IOException;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

/**
 * The machine a scenario runs against.
 * <p>
 * <strong>One suite, two targets.</strong> The same feature files run against the local VM while
 * somebody is working and against the rented machines in CI; which one is a property, not a copy
 * of the scenarios.
 * <p>
 * <strong>Host keys are not verified, deliberately.</strong> These machines are created and
 * destroyed per run - a rented server booted from a snapshot has a key nobody has seen before, and
 * pinning one would mean the suite refusing to run on the machine it was given. What this suite
 * protects is not the channel; it is what Sokar does at the far end of it.
 */
final class Machine implements AutoCloseable {

    /** How long a connection attempt may hang before the machine counts as unreachable. */
    private static final int PROBE_TIMEOUT = 4000;

    private SSHClient client;

    private final String host;

    private final String user;

    Machine() throws IOException {
        this.host = required("sokar.acceptance.host");
        this.user = required("sokar.acceptance.user");
        this.client = connected();
    }

    private SSHClient connected() throws IOException {
        final SSHClient fresh = new SSHClient();
        fresh.addHostKeyVerifier(new PromiscuousVerifier());
        fresh.connect(host);
        authenticate(fresh);
        return fresh;
    }

    /**
     * Authenticates with the key, however it was given.
     * <p>
     * <strong>The material, not only a path.</strong> On a developer's machine the key is a file.
     * In CI it is a secret that is deliberately never written to a filesystem - the reason is
     * recorded in {@code hetzner.agent}: a stray carriage return in a temporary key file fails as
     * "error in libcrypto", naming neither the file nor the reason. Taking the material directly
     * keeps that property rather than making this the one place that breaks it.
     *
     * @param client The client to authenticate.
     * @throws IOException If the key cannot be used.
     */
    private void authenticate(SSHClient client) throws IOException {
        final String material = System.getenv("SOKAR_ACCEPTANCE_KEY");
        if (material != null && !material.isBlank()) {
            // Carriage returns make an otherwise valid key unreadable, and the error says so no
            // more clearly here than it does to ssh.
            final String cleaned = material.replace("\r\n", "\n").replace("\r", "\n").strip()
                    + "\n";
            client.authPublickey(user, client.loadKeys(cleaned, null, null));
            return;
        }
        client.authPublickey(user, required("sokar.acceptance.key"));
    }

    /**
     * Tells whether the machine answers, without disturbing the session.
     *
     * @return {@code true} if something is listening and authenticating.
     */
    boolean reachable() {
        final SSHClient probe = new SSHClient();
        probe.addHostKeyVerifier(new PromiscuousVerifier());
        probe.setConnectTimeout(PROBE_TIMEOUT);
        probe.setTimeout(PROBE_TIMEOUT);
        try {
            probe.connect(host);
            probe.authPublickey(user, required("sokar.acceptance.key"));
            return true;
        } catch (IOException | RuntimeException ex) {
            // Down, or not up yet. Both are "no" here and the caller knows which it is waiting for.
            return false;
        } finally {
            try {
                probe.close();
            } catch (IOException ex) {
                // Nothing to do about a probe that will not close.
            }
        }
    }

    /** Drops the session, for a restart that is about to take it away anyway. */
    void disconnect() {
        try {
            client.disconnect();
        } catch (IOException ex) {
            // Already gone, which is what was wanted.
        }
    }

    /**
     * Opens a new session after a restart.
     *
     * @throws IOException If the machine cannot be reached.
     */
    void reconnect() throws IOException {
        client = connected();
    }

    /**
     * Returns how this machine gets restarted.
     *
     * @return The restart.
     */
    Restart restart() {
        return new SshRestart(this);
    }

    private static String required(String property) {
        final String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Give " + property
                    + " a value: this suite needs a machine to run against."
                    + " See acceptance/README.md.");
        }
        return value;
    }

    /**
     * Runs a command with no terminal, the way a script would.
     * <p>
     * Half of what this suite is for: a command that is not being watched must not ask questions,
     * must not paint anything, and must fail rather than wait.
     *
     * @param command What to run.
     * @return What it wrote and what it exited with.
     * @throws IOException If the command cannot be run.
     */
    Output run(String command) throws IOException {
        try (var session = client.startSession()) {
            final var exec = session.exec(asUser(command));
            final String out = new String(exec.getInputStream().readAllBytes());
            final String err = new String(exec.getErrorStream().readAllBytes());
            exec.join();
            final Integer status = exec.getExitStatus();
            return new Output(out, err, status == null ? -1 : status);
        }
    }

    /**
     * Opens a terminal, the way a person has one.
     *
     * @return The session.
     * @throws IOException If it cannot be opened.
     */
    Terminal terminal() throws IOException {
        return new Terminal(client);
    }

    /**
     * Returns the command as the operator would run it.
     * <p>
     * The suite connects as its own user; the machine's Sokar belongs to whoever installed it.
     * Running as that user rather than reproducing their setup is what makes a scenario a test of
     * the installed thing.
     *
     * @param command The command.
     * @return The command, wrapped.
     */
    private String asUser(String command) {
        final String as = System.getProperty("sokar.acceptance.as", "");
        return as.isBlank() || as.equals(user) ? command
                : "sudo -u " + as + " env HOME=/home/" + as + " XDG_RUNTIME_DIR=/run/user/$(id -u "
                        + as + ") bash -lc " + Shell.quote(command);
    }

    @Override
    public void close() throws IOException {
        client.disconnect();
    }

    /** What a command wrote and what it exited with. */
    record Output(String out, String err, int status) {

        /** @return Everything it wrote, whichever stream it used. */
        String all() {
            return out + err;
        }
    }
}
