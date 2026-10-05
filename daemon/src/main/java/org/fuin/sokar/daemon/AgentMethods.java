package org.fuin.sokar.daemon;

import static org.fuin.sokar.daemon.SokarDaemon.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.TaskControl;
import org.fuin.sokar.app.TaskInventory;
import org.fuin.sokar.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for agents and providers.
 * <p>
 * One class per area, so a change to one area's methods is a change to its own file; {@link SokarDaemon} only
 * says which areas there are.
 * <p>
 * Read-only answers about the machine, so an interface never shells out to the CLI to
 * find out what is installed or what is waiting.
 */
final class AgentMethods {

    private AgentMethods() {
    }

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     */
    @SuppressWarnings("unused")
    static void register(VarlinkServer server, SokarContext context, TaskInventory inventory, TaskControl control) {
        // The same rows 'sokar providers' prints, from the same class.
        server.method("Providers", (parameters, replies) ->
                replies.last(org.fuin.sokar.app.ProviderInventory.list(context).asMap()));

        server.method("Agents", (parameters, replies) -> {
            // Each agent is a process this starts and handshakes with, so it is closed again:
            // leaving them running would leak one per call.
            try (org.fuin.sokar.agent.api.InstalledAgents agents = context.agents()) {
                final List<Map<String, Object>> found = agents.all().stream().map(agent -> {
                    final Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", agent.name());
                    entry.put("label", agent.definition().label());
                    entry.put("binary", agent.definition().binary());
                    entry.put("version", agent.definition().version() == null
                            ? "" : agent.definition().version());
                    entry.put("from", agent.executable().toString());
                    // How this agent logs itself in, and where that is written down. Declared in
                    // the manifest all along and never answered here, so a client that wanted to
                    // offer "log in with the agent" had no way to know the verb - and Sokar must
                    // not guess one, because hardcoding one agent's would be wrong for the rest.
                    entry.put("loginArguments", agent.definition().loginArguments() == null
                            ? java.util.List.<String>of() : agent.definition().loginArguments());
                    // Empty when the agent declares no login at all, which is most of them: an
                    // agent whose credential is an API key has nothing to log in to.
                    entry.put("canLogIn", agent.definition().loginArguments() != null);
                    // The whole command, not the verb for a client to spell out. A client that
                    // composes 'sokar vault login <agent>' is right until the day the verb moves,
                    // and then it is wrong everywhere at once - the same reason storeCommand is
                    // answered rather than joined together from a provider list.
                    entry.put("loginCommand", agent.definition().loginArguments() == null
                            ? java.util.List.<String>of()
                            : java.util.List.of("sokar", "vault", "login", agent.name()));
                    entry.put("loginDocumentation",
                            agent.definition().loginDocumentation() == null
                                    ? "" : agent.definition().loginDocumentation());
                    entry.put("allowedDomains", agent.definition().allowedDomains());
                    // Asked for and deliberately not given. Absent from the allowed list means
                    // nobody mentioned it; this means somebody decided, and the two read the same
                    // way to anybody who is only shown the first.
                    entry.put("refusedDomains", agent.definition().refusedDomains());
                    entry.put("artifacts", agent.definition().artifacts().stream()
                            .map(SokarDaemon::artifact).toList());
                    // What an agent's commits are signed with. Nothing surfaced this until a
                    // pre-push guard started deciding on it, and "did a person or an agent write
                    // this commit?" had no answer anybody could look up.
                    final Map<String, Object> identity = new LinkedHashMap<>();
                    identity.put("name", agent.definition().gitIdentity().name());
                    identity.put("email", agent.definition().gitIdentity().email());
                    entry.put("commitsAs", identity);
                    return entry;
                }).toList();
                // Which copy of a shadowed name runs is a rule, and it lives in AgentDirectory
                // with the scan that implements it. Reported separately because a shadowed binary
                // is never started and so has no agent entry to carry a flag.
                // Two ways a binary ends up installed and never run, and one list: an earlier
                // location holds the same FILE NAME, or another binary claimed the same DECLARED
                // NAME first. Different causes, identical consequence, and an interface has one
                // thing to say about both - so they are not split into two fields nobody asked
                // for.
                final Map<Path, Path> never =
                        new LinkedHashMap<>(context.paths().agents().agentDirectory().shadowedBy());
                never.putAll(agents.ignored());
                final List<Map<String, Object>> hidden = never.entrySet().stream()
                        .map(pair -> {
                            final Map<String, Object> row = new LinkedHashMap<>();
                            row.put("path", pair.getKey().toString());
                            row.put("usedInstead", pair.getValue().toString());
                            return row;
                        }).toList();
                // What could not be asked matters as much as what could: an agent that fails to
                // describe itself is installed and unusable, and silence would read as absent.
                replies.last(Map.of("agents", found, "failures", agents.failures(),
                        "shadowed", hidden));
            }
        });

        server.method("Installable", (parameters, replies) -> {
            // Asked of this machine's own package source, never of a list kept here: every published agent
            // package provides 'sokar-agent' (the test stub does not) and every transport 'sokar-transport',
            // so a package published this morning is offered this morning.
            replies.last(Map.of("packages",
                    new org.fuin.sokar.app.InstallablePackages(context.runner()).list().stream()
                            .map(one -> Map.of("name", one.name(), "kind", one.kind(),
                                    "description", one.description(),
                                    "installed", one.installed(), "version", one.version()))
                            .toList()));
        });
    }
}
