package org.fuin.sokar.app;

import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.agent.api.InstalledAgents;
import org.jspecify.annotations.Nullable;

/**
 * Which agent a command works with: one rule, for a start and for the egress editor alike.
 * <p>
 * In the agents' area, so the egress editor can show what the same agent would be granted without reaching into the
 * task's: two rules for "which agent" would differ exactly when several are installed, which is when it matters.
 */
public final class AgentChoice {

    private AgentChoice() {
    }

    /**
     * Picks the agent a command should work with.
     * <p>
     * Shared with the egress editor, which has to show what the same agent would be granted: two
     * rules for "which agent" would differ exactly when several are installed, which is when it
     * matters.
     *
     * @param agents What is installed.
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @return The agent, or {@code null} when none is installed.
     */
    @Nullable
    public static InstalledAgent select(InstalledAgents agents, @Nullable String agentName) {
        final Choice choice = considerAgent(agents, agentName);
        if (choice.refusal() != null) {
            throw new org.fuin.sokar.agent.api.AgentException(choice.detail());
        }
        return choice.agent();
    }

    /** Why no single agent could be picked. */
    public enum AgentRefusal {

        /** A name was given and nothing is installed under it. */
        UNKNOWN_AGENT,

        /** Several are installed and the run named none, so somebody has to choose. */
        SEVERAL_AGENTS
    }

    /**
     * Which agent a run would use, as a value rather than as an exception.
     * <p>
     * The same reason {@code SelectedProvider.consider} exists: anything answering "could this
     * start" has to reach the verdict {@link #select} reaches, from the same code, and telling
     * "no such agent" from "several installed" by reading an exception's message would be parsing
     * prose for a decision.
     *
     * @param agent The agent, or {@code null} when none is installed or the choice was refused.
     * @param refusal Why it was refused, or {@code null} when it was not.
     * @param detail What to tell a person. Always set.
     */
    public record Choice(@Nullable InstalledAgent agent, @Nullable AgentRefusal refusal,
            String detail) {
    }

    /**
     * Picks the agent a command should work with, without throwing.
     *
     * @param agents What is installed.
     * @param agentName Value of {@code --agent}, or {@code null}.
     * @return The choice, refused or not.
     */
    public static Choice considerAgent(InstalledAgents agents, @Nullable String agentName) {
        if (agentName != null) {
            final InstalledAgent named = agents.find(agentName).orElse(null);
            // Installed but not started is not "not installed": the reason is what a person can act on, and leaving it
            // out read as "Found: none" while the agent was there.
            final String failed = agents.failures().get(org.fuin.sokar.agent.api.AgentDirectory.PREFIX + agentName);
            if (named == null && failed != null) {
                return new Choice(null, AgentRefusal.UNKNOWN_AGENT,
                        "The agent '" + agentName + "' is installed but did not start: " + failed);
            }
            return named != null ? new Choice(named, null, agentName)
                    : new Choice(null, AgentRefusal.UNKNOWN_AGENT,
                            "No agent named '" + agentName + "' is installed. Found: "
                                    + (agents.names().isEmpty() ? "none"
                                            : String.join(", ", agents.names())));
        }
        if (agents.size() == 1) {
            return new Choice(agents.all().getFirst(), null, agents.all().getFirst().name());
        }
        if (agents.size() == 0) {
            // A task with no agent is legitimate - the walking skeleton attaches a shell - so this
            // is not an error, only an image without an agent in it.
            return new Choice(null, null, "no agent is installed");
        }
        return new Choice(null, AgentRefusal.SEVERAL_AGENTS,
                "Several agents are installed (" + String.join(", ", agents.names())
                        + "), so --agent is required");
    }
}
