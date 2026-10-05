package org.fuin.sokar.app;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.sokar.supervisor.SocketRelay;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Listens inside a task's network namespace and forwards to the broker's socket on the host.
 * <p>
 * Started with the namespace-entering wrapper the ruleset and the resolver already use, for an
 * agent that can only be given a URL. It carries bytes and nothing else: no credential passes
 * through it that the broker has not already checked, and it resolves no names.
 */
@Command(name = "relay",
        mixinStandardHelpOptions = true,
        description = "Forwards a port in a task's namespace to the broker socket.")
public class VaultRelayCommand implements Callable<Integer> {

    @Option(names = "--listen", paramLabel = "<port>", required = true,
            description = "Loopback port to bind inside the task's network namespace.")
    private int listen;

    @Option(names = "--socket", paramLabel = "<file>", required = true,
            description = "Broker socket on the host to forward to.")
    private Path socket;

    @Option(names = "--pid-file", paramLabel = "<file>",
            description = "Where to write this process's id, so it can be reaped with the task.")
    private @Nullable Path pidFile;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws Exception {

        final java.io.PrintWriter out = spec.commandLine().getOut();

        try (SocketRelay relay = new SocketRelay(
                new java.net.InetSocketAddress("127.0.0.1", listen), socket)) {

            if (pidFile != null) {
                org.fuin.sokar.wire.HelperPid.record(pidFile);
            }
            out.println("relaying  127.0.0.1:" + listen + " -> " + socket);
            out.flush();
            relay.run();
        }
        return 0;
    }
}
