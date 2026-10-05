package org.fuin.sokar.acceptance;

import java.io.IOException;
import java.nio.file.Path;
import org.fuin.sokar.machines.Credential;
import org.fuin.sokar.machines.Ssh;
import org.jspecify.annotations.Nullable;

/**
 * The machine a scenario runs against.
 * <p>
 * <strong>One suite, two targets.</strong> The same feature files run against the local VM while
 * somebody is working and against the rented machines in CI; which one is a property, not a copy
 * of the scenarios.
 * <p>
 * <strong>What is here is what a scenario needs and a build tool does not.</strong> Connecting,
 * authenticating and running a command are {@link Ssh}, shared with the code that rents these
 * machines in the first place; the properties, the run-long connection, the terminal and the two
 * wrappers below are this suite's own.
 */
public final class Machine implements AutoCloseable {

    /**
     * The connections the run's scenarios share, one per account.
     * <p>
     * <strong>One connection, many channels.</strong> A connection per scenario meant one TCP
     * handshake and one key exchange per scenario - around eighty in two minutes - and CI's sshd
     * answered "Connection refused" to two of them 145 seconds in, with nothing wrong on either
     * side: a client that opens and drops connections that fast looks like something worth
     * refusing. Measured by counting {@code Accepted publickey} in the machine's journal: about 80
     * connections and 15.7s before, 2 and 8.5s after. A terminal stays per scenario; that is a
     * channel on this connection, which is cheap.
     * <p>
     * Cucumber has a scenario scope and the JVM, nothing between, so the run's connections live
     * here as the JVM's scope, reached through {@link #shared()} and closed by an {@code AfterAll}.
     * A {@link World} holds its account's as a member; nothing else touches the field.
     */
    private static final java.util.Map<String, Machine> OPEN = new java.util.LinkedHashMap<>();

    private final Ssh ssh;

    private final String host;

    private final String user;

    private final Credential credential;

    /**
     * Returns the connection of the account the calling thread acts as, opening it the first time it is asked for.
     * <p>
     * One connection per account for the whole run: a connection per scenario got the suite refused by sshd.
     * Which account a thread acts as is {@link Accounts}'; with one account, as an agent repository's run has,
     * this is the run's one connection it always was.
     *
     * @return The machine, as this thread's account.
     * @throws IOException If it cannot be reached or the key is refused.
     */
    public static Machine shared() throws IOException {
        return forAccount(Accounts.forThisThread());
    }

    /**
     * Returns one account's connection, opening it the first time it is asked for.
     *
     * @param user The account.
     * @return The machine, as that account.
     * @throws IOException If it cannot be reached or the key is refused.
     */
    public static synchronized Machine forAccount(String user) throws IOException {
        Machine open = OPEN.get(user);
        if (open == null) {
            open = new Machine(user);
            OPEN.put(user, open);
        }
        return open;
    }

    /**
     * Closes every connection the run opened.
     *
     * @throws IOException If one cannot be closed; the others are closed all the same.
     */
    public static synchronized void closeShared() throws IOException {
        final java.util.List<Machine> open = new java.util.ArrayList<>(OPEN.values());
        OPEN.clear();
        IOException failed = null;
        for (final Machine machine : open) {
            try {
                machine.close();
            } catch (IOException ex) {
                failed = ex;
            }
        }
        if (failed != null) {
            throw failed;
        }
    }

    /**
     * Connects to the machine the properties name, as the account they name.
     * <p>
     * A connection of its own; a scenario wants {@link #shared()} and never this.
     *
     * @throws IOException If it cannot be reached or the key is refused.
     */
    public Machine() throws IOException {
        this(required("sokar.acceptance.user"));
    }

    /**
     * Connects to the machine the properties name, as one account.
     *
     * @param user The account.
     * @throws IOException If it cannot be reached or the key is refused.
     */
    public Machine(String user) throws IOException {
        this.host = required("sokar.acceptance.host");
        this.user = user;
        this.credential = credential();
        this.ssh = Ssh.to(host, user, credential);
    }

    /**
     * Takes the key from the environment when there is one and from a file otherwise.
     * <p>
     * <strong>The material, not only a path.</strong> On a developer's machine the key is a file.
     * In CI it is a secret deliberately never written to a filesystem: a stray carriage return in
     * a temporary key file fails as "error in libcrypto", naming neither the file nor the reason.
     * Which of the two it is, and the cleaning either needs, is {@link Credential}'s.
     *
     * @return The key this suite authenticates with.
     */
    private static Credential credential() {
        final String material = System.getenv("SOKAR_ACCEPTANCE_KEY");
        if (material != null && !material.isBlank()) {
            return Credential.of(material, null);
        }
        return Credential.of(null, Path.of(required("sokar.acceptance.key")));
    }

    /**
     * Tells whether the machine answers, without disturbing the session.
     * <p>
     * Through the same credential the run uses, not a key file: in CI the key is material in the
     * environment and the file property does not exist. Asking for it here once threw inside the
     * probe, was caught as "unreachable", and made every probe answer false forever - so a restart
     * scenario failed with "the machine did not come back within 5 minutes" about a machine that
     * never went anywhere. A message accusing the infrastructure of a fault in this code is the
     * worst shape a failure can take.
     *
     * @return {@code true} if something is listening and authenticating.
     */
    public boolean reachable() {
        return Ssh.reachable(host, user, credential);
    }

    /** Drops the session, for a restart that is about to take it away anyway. */
    void disconnect() {
        ssh.disconnect();
    }

    /**
     * Opens a new session after a restart.
     *
     * @throws IOException If the machine cannot be reached.
     */
    void reconnect() throws IOException {
        ssh.reconnect();
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
    public Ssh.Output run(String command) throws IOException {
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
    public Ssh.Output run(String command, @Nullable String stdin) throws IOException {
        return ssh.run(asUser(onPath(command)), stdin);
    }

    /**
     * Opens a terminal, the way a person has one.
     *
     * @return The session.
     * @throws IOException If it cannot be opened.
     */
    public Terminal terminal() throws IOException {
        return new Terminal(ssh.client());
    }

    /**
     * Opens a terminal that echoes what is typed, as a person's does - the one a passphrase prompt is checked at.
     *
     * @return The session.
     * @throws IOException If it cannot be opened.
     */
    public Terminal echoingTerminal() throws IOException {
        return new Terminal(ssh.client(), true);
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
        ssh.close();
    }
}
