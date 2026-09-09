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

    private final SSHClient client = new SSHClient();

    private final String user;

    Machine() throws IOException {
        final String host = required("sokar.acceptance.host");
        this.user = required("sokar.acceptance.user");
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.connect(host);
        client.authPublickey(user, required("sokar.acceptance.key"));
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
