package org.fuin.sokar.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Puts this process's standard input and output onto the daemon's socket, and nothing else.
 * <p>
 * This is the other shape of remote access. With {@code ssh -L} a client forwards a local socket
 * file to the remote one and then opens it; with {@code ssh host sokar daemon connect} it speaks
 * varlink straight down the ssh session. No socket file is created on the client, nothing has to
 * be torn down, and the connection lives exactly as long as the ssh command does.
 * <p>
 * <strong>Bytes, not lines.</strong> Varlink frames are NUL-separated JSON and a stream may be
 * answered in many replies over a long time; anything that buffered by line, or waited for a
 * newline, would hold a reply until the next one arrived.
 */
@Command(name = "connect",
        mixinStandardHelpOptions = true,
        description = "Bridges standard input and output to the daemon's socket, for ssh.")
public class DaemonConnectCommand implements Callable<Integer>, SokarFactory.ContextAware {

    /** Enough for a whole reply in one read, and small enough not to matter if it is not. */
    private static final int BUFFER = 64 * 1024;

    @Option(names = "--socket", paramLabel = "<path>",
            description = "Daemon socket. Default: the one in this user's runtime directory.")
    private Path socket;

    @Spec
    private CommandSpec spec;

    private SokarContext context = SokarContext.real();

    @Override
    public void setContext(SokarContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {

        final PrintWriter err = spec.commandLine().getErr();
        final Path path = socket != null ? socket : context.paths().daemonSocket();

        if (!Files.exists(path)) {
            // Said on standard error, which ssh keeps separate from the stream: a client parsing
            // varlink must not be handed prose on the channel it is decoding.
            err.println("sokar: no daemon socket at " + path + "; start sokard on this machine");
            err.flush();
            return 69;
        }
        try {
            bridge(path, System.in, System.out);
            return 0;
        } catch (IOException ex) {
            err.println("sokar: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }

    /**
     * Copies bytes both ways until either end stops.
     * <p>
     * Two virtual threads and no interpretation of what passes through: this end has no idea
     * whether a call is streaming, and a bridge that had to know would be a second implementation
     * of the protocol living in the least useful place.
     *
     * @param path The daemon socket.
     * @param in Where calls arrive.
     * @param out Where replies go.
     * @throws IOException If the socket cannot be reached.
     */
    static void bridge(Path path, InputStream in, OutputStream out) throws IOException {

        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {

            channel.connect(UnixDomainSocketAddress.of(path));

            final OutputStream toDaemon = Channels.newOutputStream(channel);
            final Thread calls = Thread.ofVirtual().start(() -> {
                try {
                    in.transferTo(toDaemon);
                } catch (IOException ex) {
                    // The client hung up, which is how this normally ends.
                } finally {
                    try {
                        // Half-close, so a daemon waiting for the rest of a call sees the end of
                        // it rather than waiting for a client that has gone.
                        channel.shutdownOutput();
                    } catch (IOException ex) {
                        // Already closed.
                    }
                }
            });

            final ByteBuffer buffer = ByteBuffer.allocate(BUFFER);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                out.write(buffer.array(), buffer.arrayOffset() + buffer.position(),
                        buffer.remaining());
                // Every read, because the next reply of a stream may be minutes away and a
                // buffered one is indistinguishable from a daemon that has stopped answering.
                out.flush();
                buffer.clear();
            }
            calls.interrupt();
        }
    }
}
