package org.fuin.sokar.agent.api;

import java.util.List;

/**
 * What an agent module provides.
 * <p>
 * Discovered with {@link java.util.ServiceLoader}, so nothing in Sokar names an implementation.
 * Every method but {@link #definition()} has a default drawn from that definition, which is what
 * makes a well-behaved agent a YAML file and six lines of Java.
 * <p>
 * <strong>An agent that needs behavior the definition cannot express overrides a method
 * here.</strong> It does not add a branch elsewhere: a branch outside this package would be
 * invisible to the next agent, which would fall into its {@code else} and be quietly wrong.
 */
public interface Agent {

    /**
     * Returns everything about this agent that is data.
     *
     * @return The definition.
     */
    AgentDefinition definition();

    /**
     * Returns the agent's short name.
     *
     * @return Name.
     */
    default String name() {
        return definition().name();
    }

    /**
     * Returns how this agent's credential is read out of its config directory.
     *
     * @return Extractor. The default finds nothing, because an agent that stores its credential in
     *         a shape the agent API does not know about must say so rather than appear
     *         unauthenticated.
     */
    default CredentialExtractor credentialExtractor() {
        return CredentialExtractor.none();
    }

    /**
     * Returns what this agent needs placed in a container before it will run.
     *
     * @return Setup. The default places nothing, which is right for an agent that starts clean.
     */
    default ContainerSetup containerSetup() {
        return ContainerSetup.none();
    }

    /**
     * Returns how this agent's output is presented.
     *
     * @return Formatter, plain by default.
     */
    default LogFormatter logFormatter() {
        return LogFormatter.plain();
    }

    /**
     * Reads one line of this agent's output for the end of its run.
     * <p>
     * Each agent ends its output its own way, so the agent reads it: Sokar asks line by line from the end, and the
     * first line this answers for is how the run ended.
     *
     * @param line One line of the agent's raw output.
     * @return How the run ended, or {@code null} for a line that is not its end.
     */
    default @org.jspecify.annotations.Nullable AgentEnd ended(String line) {
        return null;
    }

    /**
     * Returns the container-build fragments this agent contributes, in order.
     *
     * @return Image layer.
     */
    default ImageLayer imageLayer() {
        // Fetches first: the hand-written fragments almost always operate on what was fetched,
        // and a fragment that runs before its artifact exists fails in a way that reads as a
        // problem with the fragment.
        final java.util.List<String> asAgent = new java.util.ArrayList<>(
                InstallScript.render(definition().artifacts()));
        if (!asAgent.isEmpty() && !definition().installAsAgent().isEmpty()) {
            asAgent.add("");
        }
        asAgent.addAll(definition().installAsAgent());
        return new ImageLayer(definition().installAsRoot(), java.util.List.copyOf(asAgent));
    }

    /**
     * Builds the command line for a non-interactive run.
     *
     * @param request What to run.
     * @return Command and arguments.
     */
    default List<String> headlessCommand(RunRequest request) {
        return HeadlessCommandBuilder.build(definition(), request);
    }
}
