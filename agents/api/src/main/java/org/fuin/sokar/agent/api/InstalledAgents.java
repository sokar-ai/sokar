package org.fuin.sokar.agent.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The agents installed on this machine, started and handshaken.
 * <p>
 * Replaces the in-binary registry. The difference matters: that one reported what Sokar was
 * compiled with, this one reports what is installed, so an agent added after Sokar shipped is
 * visible without Sokar changing.
 * <p>
 * <strong>One agent failing does not hide the others.</strong> A binary that will not start, or
 * speaks a protocol this Sokar does not know, is recorded as a problem against its own name and
 * the rest are still usable - otherwise a single bad package makes the machine look like it has no
 * agents at all.
 */
public class InstalledAgents implements AutoCloseable {

    private final Map<String, InstalledAgent> agents = new LinkedHashMap<>();

    private final Map<String, String> failures = new LinkedHashMap<>();

    /**
     * Starts every agent found in a directory.
     *
     * @param directory Where to look.
     * @param socketDirectory Where to create sockets.
     */
    public InstalledAgents(AgentDirectory directory, Path socketDirectory) {
        for (final Path executable : directory.executables()) {
            try {
                final InstalledAgent agent = new InstalledAgent(executable, socketDirectory);
                final InstalledAgent clash = agents.put(agent.name(), agent);
                if (clash != null) {
                    clash.close();
                    failures.put(agent.name(), "two binaries both call themselves '" + agent.name()
                            + "': " + clash.executable() + " and " + executable);
                }
            } catch (AgentException ex) {
                failures.put(executable.getFileName().toString(), ex.getMessage());
            }
        }
    }

    /**
     * Returns an agent by name.
     *
     * @param name Agent name.
     * @return The agent, or empty if none is installed under that name.
     */
    public Optional<InstalledAgent> find(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    /**
     * Returns an agent by name, or fails saying what is installed.
     *
     * @param name Agent name.
     * @return The agent.
     * @throws AgentException If there is no such agent.
     */
    public InstalledAgent require(String name) {
        return find(name).orElseThrow(() -> new AgentException(
                "No agent named '" + name + "' is installed. Found: "
                        + (names().isEmpty() ? "none" : String.join(", ", names()))));
    }

    /**
     * Returns every agent that started, ordered by name.
     *
     * @return The agents.
     */
    public List<InstalledAgent> all() {
        final List<InstalledAgent> sorted = new ArrayList<>(agents.values());
        sorted.sort(java.util.Comparator.comparing(InstalledAgent::name));
        return List.copyOf(sorted);
    }

    /**
     * Returns the names that started, ordered.
     *
     * @return Agent names.
     */
    public List<String> names() {
        return all().stream().map(InstalledAgent::name).toList();
    }

    /**
     * Returns the binaries that did not start, and why.
     * <p>
     * Reported rather than thrown: an operator needs to be told that the agent they installed is
     * not working, and needs the others to keep working while they fix it.
     *
     * @return File name to reason.
     */
    public Map<String, String> failures() {
        return Map.copyOf(failures);
    }

    /**
     * Returns how many agents are usable.
     *
     * @return Agent count.
     */
    public int size() {
        return agents.size();
    }

    @Override
    public void close() {
        agents.values().forEach(InstalledAgent::close);
        agents.clear();
    }
}
