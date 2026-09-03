package org.fuin.sokar.agent.api;

import java.util.List;

/**
 * What an agent module provides.
 * <p>
 * Discovered with {@link java.util.ServiceLoader}, so nothing in Sokar names an implementation.
 * Every method but {@link #definition()} has a default drawn from that definition, which is what
 * makes a well-behaved agent a YAML file and six lines of Java.
 * <p>
 * <strong>An agent that needs behaviour the definition cannot express overrides a method
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
     *         a shape the SPI does not know about must say so rather than appear unauthenticated.
     */
    default CredentialExtractor credentialExtractor() {
        return CredentialExtractor.none();
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
     * Returns the container-build fragments this agent contributes, in order.
     *
     * @return Image layer.
     */
    default ImageLayer imageLayer() {
        return new ImageLayer(definition().installAsRoot(), definition().installAsAgent());
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
