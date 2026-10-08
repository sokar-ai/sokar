package org.fuin.sokar.build.api;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The whole main method of a build reader: {@code describe} prints what it is, {@code serve <socket>} answers Sokar.
 */
public final class BuildMain {

    private BuildMain() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs a reader and exits with its code.
     *
     * @param reader The reader this executable serves.
     * @param arguments Command line arguments.
     */
    public static void run(final BuildReader reader, final String[] arguments) {
        System.exit(execute(reader, arguments, System.out, System.err));
    }

    /**
     * Runs a reader and returns the exit code.
     *
     * @param reader The reader.
     * @param arguments Command line arguments.
     * @param out Standard output.
     * @param err Standard error.
     * @return 0, 2 for a wrong command line, 70 when it failed.
     */
    public static int execute(final BuildReader reader, final String[] arguments, final PrintStream out,
            final PrintStream err) {

        final String mode = arguments.length > 0 ? arguments[0] : "describe";
        try {
            switch (mode) {
                case "describe" -> {
                    final Map<String, Object> answer = new LinkedHashMap<>();
                    answer.put("protocolVersion", Integer.valueOf(BuildProtocol.VERSION));
                    answer.put("forge", reader.forge());
                    out.println(org.fuin.sokar.wire.Json.write(answer));
                    return 0;
                }
                case "serve" -> {
                    if (arguments.length < 2) {
                        err.println("usage: " + reader.forge() + " serve <socket>");
                        return 2;
                    }
                    try (BuildServer server = new BuildServer(reader, Path.of(arguments[1]))) {
                        // What Sokar waits for: the socket file appears before bind() returns, so waiting for the
                        // file could connect to a socket nobody listens on.
                        out.println("ready " + server.socketPath());
                        out.flush();
                        server.serve();
                    }
                    return 0;
                }
                default -> {
                    err.println("unknown mode '" + mode + "', expected 'describe' or 'serve'");
                    return 2;
                }
            }
        } catch (RuntimeException ex) {
            err.println(reader.forge() + ": " + ex.getMessage());
            return 70;
        }
    }
}
