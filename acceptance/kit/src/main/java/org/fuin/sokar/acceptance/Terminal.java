package org.fuin.sokar.acceptance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.PTYMode;
import net.schmizz.sshj.connection.channel.direct.Session;

/**
 * A terminal on the machine, with everything that makes one a terminal.
 * <p>
 * <strong>This is the whole reason this module exists.</strong> The suite that came before it runs
 * commands over ssh without a pty, so {@code isTerminal()} is false in everything it does - and
 * every behaviour gated on that was left to a person to check by hand: the offer to start a
 * stopped task, colour on work that exists nowhere else, a passphrase that is read without being
 * echoed.
 * <p>
 * <strong>The size and the term type are set rather than inherited.</strong> A program may wrap,
 * colour or paginate differently at 80 columns than at 200, and "it looked right on my terminal"
 * is not a result. {@code ECHO} is off because the far end echoes what it chooses to: with the
 * local echo on as well, an assertion cannot tell which of the two put the text there - which is
 * exactly the question when checking that a credential was not echoed.
 * <p>
 * <strong>What a scenario may rely on</strong>, and what will not change without a note in the
 * kit's README: the terminal is {@value #COLUMNS} columns by {@value #ROWS} rows, reports itself
 * as {@value #TERM}, has echo off, and an ordinary wait gives up after {@link #PATIENCE} while a
 * wait that may build an image gives up after {@link #BUILD_PATIENCE}. A scenario that wraps a
 * line at a different width, or expects its own typing back, is written against a different
 * terminal than this one.
 */
public final class Terminal implements AutoCloseable {

    /** Columns every scenario gets, so wrapping is the same everywhere. */
    public static final int COLUMNS = 100;

    /** Rows every scenario gets. */
    public static final int ROWS = 40;

    /** What the far end is told it is talking to. */
    public static final String TERM = "xterm-256color";

    /** How long an ordinary wait may take before it is a failure rather than slowness. */
    public static final Duration PATIENCE = Duration.ofSeconds(30);

    /**
     * How long to wait for something that may have to build an image first.
     * <p>
     * Minutes, and only where a build can happen. Raising the ordinary patience to match would
     * make every wrong expectation take five minutes to fail, which is how a suite becomes
     * something people stop running.
     */
    public static final Duration BUILD_PATIENCE = Duration.ofMinutes(8);

    private final Session session;

    private final Session.Shell shell;

    private final InputStream from;

    private final OutputStream to;

    private final StringBuilder seen = new StringBuilder();

    Terminal(SSHClient client) throws IOException {
        session = client.startSession();
        session.allocatePTY(TERM, COLUMNS, ROWS, 0, 0,
                Map.of(PTYMode.ECHO, 0, PTYMode.ECHOCTL, 0));
        shell = session.startShell();
        from = shell.getInputStream();
        to = shell.getOutputStream();
    }

    /**
     * Types a line, as a person would.
     *
     * @param line What to type, without its newline.
     * @throws IOException If it cannot be sent.
     */
    public void type(String line) throws IOException {
        to.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        to.flush();
    }

    /**
     * Types text and then presses Enter, as a person at a keyboard does.
     * <p>
     * <strong>A carriage return, not a line feed.</strong> Enter sends a carriage return; a terminal
     * in cooked mode turns it into a line feed, so a shell or a passphrase prompt reads the same line
     * either way. A program in raw mode sees what was sent, and the agents' own interfaces run in raw
     * mode: they take a line feed as Ctrl-J, a new line inside the prompt, and submit nothing.
     * Measured on 2026-09-29 by Agent Smith against three agents at their pinned versions.
     * <p>
     * <strong>And on its own, after the text.</strong> Text and carriage return in one write read as a
     * paste to at least one agent's interface, and a carriage return inside a paste does not submit:
     * the prompt sat in its box. Written separately, a moment later, it does - which is how a person's
     * Enter follows their typing.
     *
     * @param text What to type before Enter, possibly empty.
     * @throws IOException If it cannot be sent.
     */
    public void enter(String text) throws IOException {
        if (!text.isEmpty()) {
            to.write(text.getBytes(StandardCharsets.UTF_8));
            to.flush();
            sleep();
        }
        to.write('\r');
        to.flush();
    }

    /**
     * Waits until the terminal has shown the given text.
     * <p>
     * <strong>Reads what has arrived rather than waiting for a line.</strong> A prompt is the case
     * that matters here and a prompt has no newline - waiting for one is how an expect-style test
     * hangs on exactly the thing it was written to check.
     *
     * @param text What to wait for.
     * @return Everything seen so far.
     * @throws IOException If the terminal cannot be read.
     */
    public String await(String text) throws IOException {
        return await(text, PATIENCE);
    }

    /**
     * Waits until the terminal has shown the given text, for as long as given.
     *
     * @param text What to wait for.
     * @param patience How long to allow.
     * @return Everything seen so far.
     * @throws IOException If the terminal cannot be read.
     */
    public String await(String text, Duration patience) throws IOException {
        final Instant deadline = Instant.now().plus(patience);
        while (!seen.toString().contains(text)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Waited " + patience.toSeconds() + "s for \"" + text
                        + "\". What the terminal showed:\n" + seen);
            }
            final int available = from.available();
            if (available <= 0) {
                sleep();
                continue;
            }
            final byte[] chunk = new byte[available];
            final int read = from.read(chunk);
            if (read > 0) {
                seen.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
            }
        }
        return seen.toString();
    }

    /**
     * Returns everything the terminal has shown so far.
     *
     * @return The output, with any escape sequences the far end sent.
     */
    public String seen() {
        return seen.toString();
    }

    /**
     * Reads whatever has arrived without waiting for anything in particular.
     *
     * @return Everything seen so far.
     * @throws IOException If the terminal cannot be read.
     */
    public String drain() throws IOException {
        sleep();
        final int available = from.available();
        if (available > 0) {
            final byte[] chunk = new byte[available];
            final int read = from.read(chunk);
            if (read > 0) {
                seen.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
            }
        }
        return seen.toString();
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() throws IOException {
        try {
            shell.close();
        } finally {
            session.close();
        }
    }
}
