package org.fuin.sokar.agent.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The agents this binary was built with.
 * <p>
 * Populated by {@link ServiceLoader}, which native-image resolves at build time from
 * {@code META-INF/services} - verified in spike S9 to need no metadata of any kind. Sokar
 * therefore never names an agent: it asks here and gets whatever was on the classpath.
 */
public class AgentRegistry {

    private final Map<String, Agent> agents;

    /**
     * Constructor taking an explicit set, for tests.
     *
     * @param discovered The agents.
     */
    public AgentRegistry(List<Agent> discovered) {
        final List<Agent> sorted = new ArrayList<>(discovered);
        sorted.sort(Comparator.comparing(Agent::name));
        final Map<String, Agent> byName = new LinkedHashMap<>();
        for (final Agent agent : sorted) {
            final Agent clash = byName.put(agent.name(), agent);
            if (clash != null) {
                // Two modules claiming one name would make which of them runs depend on classpath
                // order, which is the least debuggable kind of difference.
                throw new AgentException("Two agents both call themselves '" + agent.name() + "': "
                        + clash.getClass().getName() + " and " + agent.getClass().getName());
            }
        }
        this.agents = Map.copyOf(byName);
    }

    /**
     * Discovers the agents on the classpath.
     *
     * @return Registry of whatever was found, possibly empty.
     */
    public static AgentRegistry discover() {
        final List<Agent> found = new ArrayList<>();
        ServiceLoader.load(Agent.class).forEach(found::add);
        return new AgentRegistry(found);
    }

    /**
     * Returns an agent by name.
     *
     * @param name Agent name.
     * @return The agent, or empty if this binary has none by that name.
     */
    public Optional<Agent> find(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    /**
     * Returns an agent by name, or fails saying which ones exist.
     *
     * @param name Agent name.
     * @return The agent.
     * @throws AgentException If there is no such agent.
     */
    public Agent require(String name) {
        return find(name).orElseThrow(() -> new AgentException(
                "No agent named '" + name + "'. This build has: "
                        + (names().isEmpty() ? "none" : String.join(", ", names()))));
    }

    /**
     * Returns every agent, ordered by name.
     *
     * @return The agents.
     */
    public List<Agent> all() {
        return List.copyOf(agents.values());
    }

    /**
     * Returns the names, ordered.
     *
     * @return Agent names.
     */
    public List<String> names() {
        return List.copyOf(agents.keySet());
    }

    /**
     * Returns how many agents this binary has.
     *
     * @return Agent count.
     */
    public int size() {
        return agents.size();
    }
}
