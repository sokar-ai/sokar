package org.fuin.sokar.app;

import java.util.Map;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.AgentException;
import org.fuin.sokar.agent.api.ProviderDefinition;
import org.fuin.sokar.agent.api.ProviderRoute;
import org.jspecify.annotations.Nullable;

/**
 * The provider a task uses, and the route the agent takes to it.
 * <p>
 * Where the two declarations meet. An agent says which dialect it speaks and how it must be
 * pointed somewhere; a provider says where it is and how to address it. Neither states the
 * other's facts, so this is the only place that needs both.
 *
 * @param definition Who serves the models.
 * @param route How the proxy stands in for them for this agent.
 */
public record SelectedProvider(ProviderDefinition definition, ProviderRoute route) {

    /**
     * Chooses the provider for an agent.
     *
     * @param declared Providers found on this machine, by name.
     * @param agent The agent's own definition.
     * @param requested Provider the operator asked for, or {@code null} for the agent's default.
     * @return The selection, or {@code null} when this agent is not brokered at all.
     * @throws AgentException If the name is unknown, or the agent cannot drive it.
     */
    @Nullable
    public static SelectedProvider choose(Map<String, ProviderDefinition> declared,
            AgentDefinition agent, @Nullable String requested) {
        final Choice choice = consider(declared, agent, requested);
        if (choice.refusal() != null) {
            throw new AgentException(choice.detail());
        }
        return choice.selection();
    }

    /** Why an agent cannot be pointed at a provider. */
    public enum Refusal {

        /** The agent names no default and the run chose none, so somebody has to pick one. */
        NO_PROVIDER_CHOSEN,

        /** The provider that was asked for is not declared on this machine. */
        UNKNOWN_PROVIDER,

        /** The provider is declared but does not serve the dialect this agent speaks. */
        WRONG_DIALECT
    }

    /**
     * The outcome of choosing, as a value rather than as an exception.
     * <p>
     * Exists so that {@link #choose} and anything answering "could this start" reach the same
     * verdict from the same code. Without it a caller had to read the exception's message to find
     * out which of the three happened, which is parsing prose for a decision - the thing this
     * contract forbids everywhere else.
     *
     * @param selection The provider, or {@code null} when there is none to choose or the choice
     *        was refused.
     * @param refusal Why it was refused, or {@code null} when it was not.
     * @param wanted The provider name that was looked for, or {@code null} when none was named.
     * @param detail What to tell a person. Always set.
     */
    public record Choice(@Nullable SelectedProvider selection, @Nullable Refusal refusal,
            @Nullable String wanted, String detail) {
    }

    /**
     * Works out which provider an agent would use, without throwing.
     *
     * @param declared Providers found on this machine, by name.
     * @param agent The agent's own definition.
     * @param requested Provider the operator asked for, or {@code null} for the agent's default.
     * @return The choice, refused or not.
     */
    public static Choice consider(Map<String, ProviderDefinition> declared,
            AgentDefinition agent, @Nullable String requested) {

        if (agent.provider() == null) {
            // Not an error: the agent takes its credential directly and there is nothing to
            // stand in for.
            return new Choice(null, null, null, "'" + agent.name() + "' is not brokered");
        }
        final String wanted = requested != null ? requested : agent.provider().defaultProvider();
        if (wanted == null) {
            return new Choice(null, Refusal.NO_PROVIDER_CHOSEN, null,
                    "'" + agent.name() + "' names no default provider, so one has to be chosen"
                            + " with --provider. Declared: " + names(declared));
        }
        final ProviderDefinition provider = declared.get(wanted);
        if (provider == null) {
            return new Choice(null, Refusal.UNKNOWN_PROVIDER, wanted,
                    "No provider '" + wanted + "' is declared. Found: " + names(declared));
        }
        if (!agent.provider().canDrive(provider)) {
            // Named rather than attempted: the request would otherwise go out in a wire format
            // the provider does not serve and come back as a confusing model error.
            return new Choice(null, Refusal.WRONG_DIALECT, wanted,
                    "'" + agent.name() + "' speaks '" + agent.provider().dialect()
                            + "', which '" + wanted + "' does not serve - it offers "
                            + provider.dialects().keySet());
        }
        return new Choice(new SelectedProvider(provider,
                ProviderRoute.of(provider, agent.provider())), null, wanted, wanted);
    }

    /**
     * Returns the vault key a task's credential is stored under.
     * <p>
     * The provider's name, because the credential is the provider's: two agents reaching the same
     * provider must find one entry, not store two copies of the same secret under their own
     * names.
     * <p>
     * <strong>An older vault still works.</strong> An entry under the agent's own name is used
     * when there is none under the provider's, so upgrading does not stop anyone authenticating.
     * When neither exists the provider's name is returned, because that is where a new one
     * belongs and it is the name the "no credential" message should say.
     *
     * @param stored Names the vault currently holds.
     * @param agentName The agent's own name.
     * @param providerName The provider serving this task, or {@code null} when it is not brokered.
     * @return Key to look the credential up by.
     */
    public static String credentialKey(java.util.Set<String> stored, String agentName,
            @Nullable String providerName) {
        if (providerName == null) {
            return agentName;
        }
        if (!stored.contains(providerName) && stored.contains(agentName)) {
            return agentName;
        }
        return providerName;
    }

    private static String names(Map<String, ProviderDefinition> declared) {
        return declared.isEmpty() ? "none" : String.join(", ", declared.keySet());
    }

    /**
     * Returns the provider's name.
     *
     * @return Short name.
     */
    public String name() {
        return definition.name();
    }
}
