package org.fuin.sokar.app;

import java.io.PrintWriter;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.jspecify.annotations.Nullable;

/**
 * Which credential a task will use, and what to tell the operator about it.
 * <p>
 * Split out of {@code TaskRunCommand} with {@link EgressReport} and {@link AgentStaging}. The
 * three questions here - which provider, which vault key, which variable - are asked from several
 * places in a run and answered from the same three inputs, and the warnings beside them are the
 * ones an operator needs before a task fails on authentication rather than after.
 * <p>
 * The provider is resolved once and remembered: choosing it consults the installed providers and
 * the agent's own definition, and asking twice could answer differently if either changed under a
 * running task.
 */
final class CredentialChoice {

    /** Used when neither the flag nor the stored entry says which kind this is. */
    private static final String DEFAULT_CREDENTIAL_TYPE = "api-key";

    private final SokarContext context;

    private final @Nullable String providerName;

    private final @Nullable String requestedType;

    private final @Nullable String agentName;

    private boolean providerResolved;

    private @Nullable SelectedProvider provider;

    /**
     * Constructor with what the operator asked for.
     *
     * @param context Where the providers and the vault come from.
     * @param providerName Value of {@code --provider}, or {@code null}.
     * @param requestedType Value of {@code --credential-type}, or {@code null}.
     * @param agentName Value of {@code --agent}, or {@code null}.
     */
    CredentialChoice(SokarContext context, @Nullable String providerName,
            @Nullable String requestedType, @Nullable String agentName) {
        this.context = context;
        this.providerName = providerName;
        this.requestedType = requestedType;
        this.agentName = agentName;
    }

    /**
     * Returns the name this task's credential is stored under.
     * <p>
     * <strong>The provider's name, not the agent's.</strong> A credential belongs to whoever
     * issued it: pointing a second agent at a provider the first already uses meant storing the
     * same key twice, under two agent names, with neither saying which provider it was for.
     * <p>
     * A vault written before that change is still read: an entry under the agent's own name is
     * used when there is none under the provider's, so nobody's stored credential stops working
     * on upgrade. {@link #reportLegacyCredential} is what tells them to move it.
     *
     * @param agent The agent.
     * @return Vault key.
     */
    String credentialName(org.fuin.sokar.agent.api.InstalledAgent agent) {
        final SelectedProvider selection = provider(agent);
        return SelectedProvider.credentialKey(context.credentials().keySet(), agent.name(),
                selection == null ? null : selection.name());
    }

    /**
     * Says once that a credential is stored under the old key.
     *
     * @param agent The agent.
     * @param out Where to report.
     */
    void reportLegacyCredential(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            InstalledAgent agent, PrintWriter out) {
        if (agent == null) {
            return;
        }
        final SelectedProvider selection = provider(agent);
        if (selection != null && credentialName(agent).equals(agent.name())
                && !agent.name().equals(selection.name())) {
            if (offer != Offer.NOBODY && offer.confirm("credential stored under '" + agent.name() + "', which is this"
                    + " agent's name; it belongs to '" + selection.name() + "'", "Move it there now, without entering it"
                    + " again?", "", false)) {
                try {
                    final var opener = context.opener();
                    if (opener.isPresent()) {
                        context.vault().update(opener.get(), entries -> moved(entries, agent.name(), selection.name()));
                        out.println("credential moved to '" + selection.name() + "'");
                        return;
                    }
                } catch (final RuntimeException ex) {
                    out.println("credential could not be moved: " + ex.getMessage());
                }
            }
            out.println("credential stored under '" + agent.name() + "', which is this agent's"
                    + " name; it belongs to '" + selection.name() + "'. Move it with:"
                    + " sokar vault put " + selection.name());
        }
    }

    /** Who is offered a remedy where a note would name a command: nobody, unless a terminal is there. */
    private Offer offer = Offer.NOBODY;

    /**
     * Offers what a note would name, through the given helper.
     *
     * @param offer The helper.
     * @return This choice.
     */
    CredentialChoice offering(final Offer offer) {
        this.offer = offer;
        return this;
    }

    /**
     * Returns the entries with one moved to another name, whole - never over what that name holds already.
     *
     * @param entries The vault's entries.
     * @param from The name it is under.
     * @param to The name it belongs under.
     * @return The entries after the move.
     */
    static java.util.Map<String, org.fuin.sokar.vault.VaultEntry> moved(
            final java.util.Map<String, org.fuin.sokar.vault.VaultEntry> entries, final String from, final String to) {
        if (entries.containsKey(from) && !entries.containsKey(to)) {
            entries.put(to, entries.remove(from));
        }
        return entries;
    }

    /**
     * Returns the variable this task's credential belongs in.
     * <p>
     * The agent's own answer if it gives one, otherwise the provider's. Most agents no longer
     * give one: which variable an Anthropic key goes in is Anthropic's fact, and an agent that
     * restated it could disagree with the agent beside it.
     *
     * @param agent The agent.
     * @return Variable name, or {@code null} when neither names one.
     */
    @org.jspecify.annotations.Nullable
    String tokenVariable(org.fuin.sokar.agent.api.InstalledAgent agent) {
        final String type = credentialType(credentialName(agent));
        final SelectedProvider selection = provider(agent);
        return selection == null ? agent.definition().tokenVariable(type)
                : agent.definition().tokenVariable(type, selection.definition());
    }

    /**
     * Returns the provider this task uses, resolving it the first time it is asked for.
     *
     * @param agent The agent, or {@code null}.
     * @return The selection, or {@code null} when nothing is brokered.
     */
    @org.jspecify.annotations.Nullable
    SelectedProvider provider(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            InstalledAgent agent) {
        if (!providerResolved) {
            providerResolved = true;
            provider = agent == null ? null
                    : SelectedProvider.choose(context.providers(), agent.definition(),
                            providerName);
        }
        return provider;
    }

    /**
     * Reports a vault copy that has fallen behind, without stopping anything.
     *
     * @param agent The selected agent, or {@code null}.
     * @param out Where to report.
     */
    void reportStaleCredential(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            InstalledAgent agent, PrintWriter out) {
        if (agent == null || agent.definition().configDirectory() == null) {
            return;
        }
        try {
            if (staleCredential(context.credentials().get(credentialName(agent)),
                    agent.extractCredential(VaultImportCommand
                            .expand(agent.definition().configDirectory())).orElse(null))) {
                if (offer != Offer.NOBODY && offer.confirm("the vault's copy of the credential is older than the one '"
                        + agent.name() + "' holds here", "Refresh it now, as 'sokar vault import " + agent.name()
                        + "' does?", "", false) && context.exec().applyAsInt(java.util.List.of(SokarBinary.path(),
                                "vault", "import", agent.name())) == 0) {
                    out.println("credential refreshed from '" + agent.name() + "'");
                    return;
                }
                out.println("credential the vault's copy is older than the one '" + agent.name()
                        + "' holds here; 'sokar vault import " + agent.name() + "' refreshes it");
            }
        } catch (RuntimeException ex) {
            // A freshness check must never be the reason a task does not run.
            return;
        }
    }

    /**
     * Says why this task cannot authenticate, before anything is built.
     *
     * @param agent The selected agent, or {@code null}.
     * @return The reason, or {@code null} when there is nothing in the way.
     */
    @org.jspecify.annotations.Nullable
    String unbrokerable(org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            InstalledAgent agent) {
        final SelectedProvider selection = provider(agent);
        if (agent == null || selection == null) {
            return null;
        }
        final String type = credentialType(credentialName(agent));
        final String reason = selection.route().unbrokerableReason(type);
        return reason == null ? null
                : "the '" + type + "' credential stored for '" + agent.name()
                        + "' cannot be used. " + reason + ".";
    }

    /**
     * Returns which credential kind this task uses.
     * <p>
     * The kind belongs to the secret, so the stored entry decides it and the flag only overrides.
     * Before it was stored the flag was the only source, and forgetting it failed looking exactly
     * like a wrong key.
     *
     * @param agentName Name the credential is stored under.
     * @return The kind.
     */
    String credentialType(String agentName) {
        if (requestedType != null) {
            return requestedType;
        }
        final var entry = context.credentials().get(agentName);
        return entry == null || entry.type() == null ? DEFAULT_CREDENTIAL_TYPE : entry.type();
    }

    /**
     * Says whether the vault's copy has fallen behind the agent's own credential.
     * <p>
     * Only when both are the same kind: an operator who stored a different kind on purpose has
     * not gone stale, and warning them every run would teach them to ignore the line.
     *
     * @param stored What the vault holds, or {@code null}.
     * @param host What the agent holds on this machine, or {@code null}.
     * @return {@code true} when the vault should be refreshed.
     */
    static boolean staleCredential(org.fuin.sokar.vault.@org.jspecify.annotations.Nullable
            VaultEntry stored, org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable
            Credential host) {
        return stored != null && host != null
                && java.util.Objects.equals(stored.type(), host.type())
                && !stored.value().equals(host.secret());
    }
}
