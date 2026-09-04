package org.fuin.sokar.agent.api;

/**
 * The contract between Sokar and an agent binary.
 * <p>
 * Sokar and the agents are packaged, versioned and installed separately, so at some point an
 * operator will have a combination nobody tested. The version here is what makes that a clear
 * message instead of a strange failure: an agent states which protocol it speaks, and Sokar
 * refuses one it does not understand rather than guessing at a partially-matching shape.
 */
public final class AgentProtocol {

    /** Varlink interface both sides implement. */
    public static final String INTERFACE = "org.fuin.sokar.Agent1";

    /**
     * Version of this contract.
     * <p>
     * Raised when a change would make an older Sokar misread a newer agent, or the reverse. Adding
     * an optional field does not qualify; renaming or removing one does.
     */
    public static final int VERSION = 2;

    /** Returns the agent's definition and the protocol version it speaks. */
    public static final String DESCRIBE = INTERFACE + ".Describe";

    /** Reads the agent's credential out of a config directory. */
    public static final String EXTRACT_CREDENTIAL = INTERFACE + ".ExtractCredential";

    /** Returns the files an agent needs placed in a container before it starts. */
    public static final String CONTAINER_SETUP = INTERFACE + ".ContainerSetup";

    /** Builds the command line for a non-interactive run. */
    public static final String BUILD_COMMAND = INTERFACE + ".BuildCommand";

    /** Streams formatted log lines read from a file. */
    public static final String FORMAT_LOG = INTERFACE + ".FormatLog";

    private AgentProtocol() {
        throw new UnsupportedOperationException("Utility class");
    }
}
