package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Prints the line a peer adds to their {@code allowed_signers} file to accept messages from here.
 * <p>
 * The public half only. Without this, telling a peer who you are means reading a key file by hand
 * and hoping the private half was not read with it - so the command exists to make the safe way the
 * easy one.
 */
@Command(name = "key",
        mixinStandardHelpOptions = true,
        description = "Prints this machine's signing key, as a peer's allowed_signers line.")
public class TalkKeyCommand implements Callable<Integer>, SokarFactory.ContextAware {

    @Option(names = "--as", paramLabel = "<principal>",
            description = "What to call this machine in the line. Default: sokar@<hostname>")
    private @Nullable String principal;

    @Option(names = "--publish",
            description = "Also writes the line to this machine's shared key directory, so the"
                    + " other users here can verify what this account sends.")
    private boolean publish;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(final SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        final PrintWriter out = spec.commandLine().getOut();
        final String name = principal == null ? "sokar@" + hostName() : principal;
        // Created on first use rather than demanded: a machine that has never sent a message has no
        // reason to have been asked for a key, and this is often the first thing anybody asks for.
        final var key = HostKey.loadOrCreate(context.paths().messageKey(), name);
        final String line = HostKey.allowedSignersLine(key, name);
        out.println(line);
        if (publish) {
            final int code = publish(line, out, spec.commandLine().getErr());
            out.flush();
            return code;
        }
        out.flush();
        return 0;
    }

    /**
     * Writes this account's key where the other users of this machine can read it.
     * <p>
     * <strong>Each account publishes its own, and that is the point.</strong> The file is owned by
     * the user it is named after, and a reader checks that before believing it - so somebody else
     * creating {@code bob.pub} produces a file owned by them, which is refused. Root could not do
     * this on anybody's behalf anyway: a signing key is made on first use, in that user's own state
     * directory.
     *
     * @param line The allowed_signers line to publish.
     * @param out Where progress goes.
     * @param err Where a refusal goes.
     * @return Exit code.
     */
    private int publish(final String line, final java.io.PrintWriter out,
            final java.io.PrintWriter err) {
        final java.nio.file.Path directory = context.paths().sharedKeys();
        if (!java.nio.file.Files.isDirectory(directory)) {
            err.println("sokar: " + directory + " does not exist, so messaging between the users of"
                    + " this machine has not been allowed here.");
            err.println("       ask an administrator for: sokar-setup.sh --between-users on");
            err.flush();
            return 1;
        }
        final String me = System.getProperty("user.name", "");
        // Into this account's own directory, which root made and only this account may write.
        final java.nio.file.Path mine = directory.resolve(me);
        if (!java.nio.file.Files.isDirectory(mine)) {
            err.println("sokar: " + mine + " does not exist, so this account has no place to"
                    + " publish to.");
            err.println("       ask an administrator for: sokar-setup.sh --user " + me);
            err.flush();
            return 1;
        }
        final java.nio.file.Path file = mine.resolve(SharedKeys.FILE);
        try {
            java.nio.file.Files.writeString(file, line + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
            out.println("published " + file);
            return 0;
        } catch (final java.io.IOException ex) {
            // Most likely somebody else got there first: the directory is sticky, so a file that
            // is not yours cannot be replaced. That is the protection working, not a fault.
            err.println("sokar: cannot write " + file + " - " + ex.getMessage());
            if (java.nio.file.Files.exists(file)) {
                err.println("       it exists and is not yours, which should not be possible in a"
                        + " directory of your own - tell whoever set this machine up");
            }
            err.flush();
            return 1;
        }
    }

    private String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (final java.net.UnknownHostException e) {
            return "localhost";
        }
    }
}
