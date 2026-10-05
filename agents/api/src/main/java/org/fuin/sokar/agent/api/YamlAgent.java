package org.fuin.sokar.agent.api;

/**
 * Base class for an agent whose definition lives in its own module's resources.
 * <p>
 * This is what reduces a well-behaved agent to a YAML file and a constructor:
 * <pre>
 * public final class ExampleAgent extends YamlAgent {
 *     public ExampleAgent() {
 *         super("example");
 *     }
 * }
 * </pre>
 * The definition is read once, when the class is constructed, so a malformed one fails at
 * discovery rather than in the middle of a task.
 */
public abstract class YamlAgent implements Agent {

    private final AgentDefinition definition;

    /**
     * Reads {@code /agent/<name>.yaml} from the subclass's own module.
     *
     * @param name Agent name, which is also the resource name.
     */
    protected YamlAgent(String name) {
        this.definition = AgentDefinitionReader.fromResource(getClass(), "/agent/" + name + ".yaml");
        if (!this.definition.name().equals(name)) {
            // Otherwise an agent could be discovered under one name and describe itself as
            // another, and the roster would disagree with the file it came from.
            throw new AgentException("Agent '" + name + "' loads a definition naming itself '"
                    + this.definition.name() + "'");
        }
    }

    @Override
    public final AgentDefinition definition() {
        return definition;
    }

    @Override
    public String toString() {
        return definition.name();
    }
}
