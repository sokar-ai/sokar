package org.fuin.sokar.agent.api;

import java.nio.file.Path;
import java.util.Map;

/**
 * The {@code main} every agent binary shares.
 * <p>
 * An agent's own entry point is three lines:
 * <pre>
 * public static void main(String[] args) {
 *     AgentMain.run(new ExampleAgent(), args);
 * }
 * </pre>
 * Two modes, and both are useful. {@code serve} is what Sokar spawns.
 * {@code describe} prints the definition as JSON, which makes an installed agent inspectable with
 * nothing but the binary and {@code jq} - worth having when an operator is asking why Sokar will
 * not use an agent they just installed.
 */
public final class AgentMain {

    private AgentMain() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Runs an agent binary.
     *
     * @param agent The agent this binary serves.
     * @param arguments Command line arguments.
     */
    public static void run(Agent agent, String[] arguments) {
        System.exit(execute(agent, arguments, System.out, System.err));
    }

    /**
     * Runs an agent binary and returns the exit code.
     *
     * @param agent The agent this binary serves.
     * @param arguments Command line arguments.
     * @param out Standard output.
     * @param err Standard error.
     * @return Exit code.
     */
    public static int execute(Agent agent, String[] arguments,
            java.io.PrintStream out, java.io.PrintStream err) {

        final String mode = arguments.length > 0 ? arguments[0] : "describe";

        try {
            switch (mode) {
                case "describe" -> {
                    out.println(org.fuin.sokar.wire.Json.write(Map.of(
                            "protocolVersion", Integer.valueOf(AgentProtocol.VERSION),
                            "definition", AgentDefinitionJson.write(agent.definition()))));
                    return 0;
                }
                case "serve" -> {
                    if (arguments.length < 2) {
                        err.println("usage: " + agent.name() + " serve <socket>");
                        return 2;
                    }
                    try (AgentServer server = new AgentServer(agent, Path.of(arguments[1]))) {
                        // Printed so a caller waiting for readiness has something to wait for
                        // other than a sleep.
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
            err.println(agent.name() + ": " + ex.getMessage());
            return 70;
        }
    }
}
