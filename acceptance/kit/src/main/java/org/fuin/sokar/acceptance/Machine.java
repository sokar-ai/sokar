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
public final class Machine implements AutoCloseable {

    /** How long a connection attempt may hang before the machine counts as unreachable. */
    private static final int PROBE_TIMEOUT = 4000;

    /**
     * The connection every scenario in the run shares.
     * <p>
     * <strong>One connection, many channels.</strong> A connection per scenario meant one TCP
     * handshake and one key exchange per scenario - around eighty in two minutes - and CI's sshd
     * answered "Connection refused" to two of them 145 seconds in, with nothing wrong on either
     * side: a client that opens and drops connections that fast looks like something worth
     * refusing. Measured by counting {@code Accepted publickey} in the machine's journal: about 80
     * connections and 15.7s before, 2 and 8.5s after. A terminal stays per scenario; that is a
     * channel on this connection, which is cheap.
     * <p>
     * Cucumber has a scenario scope and the JVM, nothing between, so the run's connection lives
     * here as the JVM's scope, reached through {@link #shared()} and closed by an {@code AfterAll}.
     * A {@link World} holds it as a member; nothing else touches the field.
     */
    private static @org.jspecify.annotations.Nullable Machine shared;

    private SSHClient client;

    private final String host;

    private final String user;

    /**
     * Returns the run's connection, opening it the first time it is asked for.
     *
     * @return The machine.
     * @throws IOException If it cannot be reached or the key is refused.
     */
    public static synchronized Machine shared() throws IOException {
        if (shared == null) {
            shared = new Machine();
        }
        return shared;
    }

    /**
     * Closes the run's connection, if one was opened.
     *
     * @throws IOException If it cannot be closed.
     */
    public static synchronized void closeShared() throws IOException {
        if (shared != null) {
            final Machine open = shared;
            shared = null;
            open.close();
        }
    }

    /**
     * Connects to the machine the properties name.
     * <p>
     * A connection of its own; a scenario wants {@link #shared()} and never this.
     *
     * @throws IOException If it cannot be reached or the key is refused.
     */
    public Machine() throws IOException {
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
    public boolean reachable() {
        final SSHClient probe = new SSHClient();
        probe.addHostKeyVerifier(new PromiscuousVerifier());
        probe.setConnectTimeout(PROBE_TIMEOUT);
        probe.setTimeout(PROBE_TIMEOUT);
        try {
            probe.connect(host);
            // Through authenticate(), not the key file: in CI the key is material in the
            // environment and the file property does not exist. Asking for it here threw inside
            // this try, was caught as "unreachable", and made every probe answer false forever -
            // so a restart scenario failed with "the machine did not come back within 5
            // minutes" about a machine that never went anywhere. A message accusing the
            // infrastructure of a fault in this code is the worst shape a failure can take.
            authenticate(probe);
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
    public Restart restart() {
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
    public Output run(String command) throws IOException {
        return run(command, null);
    }

    /**
     * Runs a command with no terminal and feeds it standard input, the way a script would.
     * <p>
     * <strong>This is how a secret reaches the machine.</strong> A command line is readable by
     * every process there and lands in shell history; standard input is neither. It is the same
     * rule {@code vault put} follows, applied to the suite that tests it.
     *
     * @param command What to run.
     * @param stdin What to feed it, or {@code null} for nothing.
     * @return What it wrote and what it exited with.
     * @throws IOException If the command cannot be run.
     */
    public Output run(String command, String stdin) throws IOException {
        try (var session = client.startSession()) {
            final var exec = session.exec(asUser(onPath(command)));
            if (stdin != null) {
                try (var in = exec.getOutputStream()) {
                    in.write(stdin.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
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
    public Terminal terminal() throws IOException {
        return new Terminal(client);
    }

    /**
     * Puts the command where an unprivileged install can be found.
     * <p>
     * <strong>Stated rather than inherited</strong>, the same rule the remote build already
     * follows for {@code JAVA_HOME}: an ssh command is neither a login nor an interactive shell,
     * so nothing sources the profile that would add {@code ~/.local/bin}. A packaged install is
     * in {@code /usr/bin} and works either way; the unprivileged install the rented machines use
     * is not, and the whole suite answered 127 on its first run in CI because of it.
     *
     * @param command The command.
     * @return The command, with a path that finds sokar however it was installed.
     */
    private static String onPath(String command) {
        return "bash -c " + Shell.quote("export PATH=\"$HOME/.local/bin:$PATH\"; " + command);
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

    /**
     * What a command wrote and what it exited with.
     *
     * @param out Standard output.
     * @param err Standard error.
     * @param status Exit status, or {@code -1} when the far end reported none.
     */
    public record Output(String out, String err, int status) {

        /**
         * Returns everything it wrote, whichever stream it used.
         *
         * @return Both streams, standard output first.
         */
        public String all() {
            return out + err;
        }
    }
}
