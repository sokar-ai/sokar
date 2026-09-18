package org.fuin.sokar.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
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
    private String principal;

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
        out.println(HostKey.allowedSignersLine(key, name));
        out.flush();
        return 0;
    }

    private String hostName() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (final java.net.UnknownHostException e) {
            return "localhost";
        }
    }
}
