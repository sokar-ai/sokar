package org.fuin.sokar.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.sokar.clearance.ClearanceService;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.fuin.sokar.wire.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Watches the blocked connections a running watcher is seeing.
 * <p>
 * This is what the varlink transport is for. A pipe has exactly one reader; a subscription can
 * have several, so a terminal can follow along while the desktop prompt does the asking.
 */
@Command(name = "subscribe",
        mixinStandardHelpOptions = true,
        description = "Follows the blocked connections a running watcher reports.")
public class ShieldSubscribeCommand implements Callable<Integer> {

    @Option(names = "--socket", paramLabel = "<path>", required = true,
            description = "Clearance socket of a running 'sokar shield watch'.")
    private Path socket;

    @Option(names = "--count", paramLabel = "<n>",
            description = "Stop after this many events. Zero means follow until killed.")
    private int count;

    @Spec
    private CommandSpec spec;

    @Override
    public Integer call() throws IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final int[] seen = { 0 };

        try (VarlinkClient client = new VarlinkClient(socket)) {
            client.callMore(ClearanceService.INTERFACE + ".Subscribe", Map.of(), event -> {
                out.println(Json.write(event));
                out.flush();
                return count == 0 || ++seen[0] < count;
            });
        }
        return 0;
    }
}
