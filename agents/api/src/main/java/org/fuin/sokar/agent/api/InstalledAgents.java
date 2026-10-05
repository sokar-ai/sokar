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

    private final Map<Path, Path> ignored = new LinkedHashMap<>();

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
                final InstalledAgent running = agents.putIfAbsent(agent.name(), agent);
                if (running != null) {
                    // The FIRST one found wins, not the last. It used to be the last, which
                    // silently inverted the rule the rest of this class is built on: locations are
                    // searched most specific first, so overwriting meant a packaged agent beat the
                    // operator's own copy of the same name - the opposite of what every other
                    // precedence in Sokar does, and invisible when wrong, because both copies look
                    // plausible and the difference only shows up as a run behaving unlike the
                    // version somebody read on screen.
                    agent.close();
                    // NOT a failure. The agent works - one copy of it is running - so calling this
                    // unusable would be false, and it used to make 'sokar agents' exit non-zero on
                    // a machine where nothing was wrong. It belongs with the other binary that is
                    // installed and never runs.
                    ignored.put(executable, running.executable());
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
    /**
     * Returns binaries that never run because another one claimed their name first.
     * <p>
     * Distinct from {@link #failures()} on purpose: these are not broken, and the agent they
     * declare is running. What is wrong is only that somebody installed a second copy and cannot
     * see which one they are getting - the failure that is invisible when it happens, because both
     * copies look plausible and the difference shows up as a run behaving unlike the version on
     * screen.
     * <p>
     * The first one found wins, and locations are searched most specific first, so the operator's
     * own copy beats a packaged one. That is a rule rather than scan order, which is why it can be
     * reported at all.
     *
     * @return Binary that is ignored, to the one that runs instead.
     */
    public Map<Path, Path> ignored() {
        return java.util.Collections.unmodifiableMap(ignored);
    }

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
