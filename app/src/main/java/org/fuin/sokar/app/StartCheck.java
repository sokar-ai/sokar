package org.fuin.sokar.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.sokar.agent.api.InstalledAgent;
import org.fuin.sokar.agent.api.InstalledAgents;
import org.jspecify.annotations.Nullable;

/**
 * Whether work could start here, and if not, what somebody has to do about it.
 * <p>
 * <strong>Asked before anything is created, and answered from one place.</strong> Until this
 * existed, the only way to find out was to start a task and read what came back: an exit code and
 * a stream of prose. Parsing that to learn which credential was missing is parsing prose for a
 * decision, which is the thing this contract refuses everywhere else.
 * <p>
 * The rule needs four inputs and a client has one of them - the agent's own declaration, the
 * providers installed here, the run's overrides, and what the vault currently holds. The last is
 * why a field on {@code Agent} could not have answered it: a vault written before credentials were
 * keyed by provider still answers under the agent's own name, so <em>which name to look for
 * depends on what the vault already holds</em>, and a client intersecting two lists would report a
 * missing credential for precisely the vault that has one.
 * <p>
 * Every decision here is delegated rather than restated - {@link TaskLaunch#considerAgent},
 * {@link SelectedProvider#consider}, {@link CredentialWiring#credentialUnavailable} - so this and
 * an actual launch cannot come to different conclusions.
 */
public final class StartCheck {

    /**
     * Why work could not start, or that it could.
     * <p>
     * Values may be added; render an unrecognised one rather than throwing, by the same rule as
     * every other outcome in this contract.
     */
    public enum Outcome {

        /** Nothing is in the way. */
        READY,

        /** The project file is not there, or cannot be read. */
        NO_PROJECT_FILE,

        /** The task name is one Start would refuse. {@code detail} says why. */
        BAD_TASK_NAME,

        /** Nothing is installed to run. A task with no agent can still be started as a shell. */
        NO_AGENT,

        /** A name was given and nothing is installed under it. */
        UNKNOWN_AGENT,

        /** Several are installed and none was named, so somebody has to choose. */
        SEVERAL_AGENTS,

        /** The agent names no default provider and the run chose none. Pick a provider, not a
         *  secret - a different action from the ones below. */
        NO_PROVIDER_CHOSEN,

        /** The provider that was asked for is not declared here. */
        UNKNOWN_PROVIDER,

        /** The provider is declared but does not serve the dialect this agent speaks. */
        WRONG_DIALECT,

        /** The vault cannot be read, so this cannot be answered until somebody unlocks it. Never
         *  to be shown as a missing credential: unlocking is the action, and telling somebody to
         *  store a credential they already have is the one instruction that cannot help. */
        VAULT_LOCKED,

        /** The vault is readable and holds nothing under the name this run would look for.
         *  {@code credential} names it. */
        CREDENTIAL_MISSING,

        /** Something is stored and cannot be brokered - the wrong kind for this provider.
         *  {@code detail} says what. */
        CREDENTIAL_UNUSABLE
    }

    /**
     * What a check found.
     *
     * @param outcome Why work could not start, or {@link Outcome#READY}.
     * @param agent The agent this was answered for, or "" when none was settled.
     * @param provider The provider serving it, or "" when it is not brokered or none was settled.
     * @param credential The vault key this run would look for, or "" when it never got that far.
     *        The name that was ACTUALLY looked for, not the one that ought to apply.
     * @param detail Prose for a person. Never parsed.
     */
    public record Result(Outcome outcome, String agent, String provider, String credential,
            String detail) {

        /** @return Whether work could start. */
        public boolean ready() {
            return outcome == Outcome.READY;
        }

        /**
         * Returns this as plain values, for a caller that has to put it on a wire.
         *
         * @return The result.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("ready", ready());
            map.put("outcome", outcome.name());
            map.put("agent", agent);
            map.put("provider", provider);
            map.put("credential", credential);
            map.put("detail", detail);
            return map;
        }
    }

    private StartCheck() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static Result refused(Outcome outcome, String detail) {
        return new Result(outcome, "", "", "", detail);
    }

    /**
     * Answers whether a run with these choices could start.
     *
     * @param context Where the agents, providers and vault come from.
     * @param projectFile The project file, or {@code null} not to check one.
     * @param agentName Agent to run, or {@code null} for the only one installed.
     * @param providerName Provider to route through, or {@code null} for the agent's own default.
     * @param credentialType Overrides the stored credential kind, or {@code null}.
     * @return What was found.
     */
    public static Result check(SokarContext context, @Nullable Path projectFile,
            @Nullable String agentName, @Nullable String providerName,
            @Nullable String credentialType) {
        return check(context, projectFile, null, agentName, providerName, credentialType);
    }

    /**
     * Answers whether a run with these choices, under this task name, could start.
     *
     * @param context Where the agents, providers and vault come from.
     * @param projectFile The project file, or {@code null} not to check one.
     * @param taskName The task name Start would be given, or {@code null} not to check one.
     * @param agentName Agent to run, or {@code null} for the only one installed.
     * @param providerName Provider to route through, or {@code null} for the agent's own default.
     * @param credentialType Overrides the stored credential kind, or {@code null}.
     * @return What was found.
     */
    public static Result check(SokarContext context, @Nullable Path projectFile,
            @Nullable String taskName, @Nullable String agentName, @Nullable String providerName,
            @Nullable String credentialType) {

        if (projectFile != null && !Files.isRegularFile(projectFile)) {
            return refused(Outcome.NO_PROJECT_FILE, "no project file at " + projectFile);
        }

        if (taskName != null) {
            // The rule Start refuses by, from the same method. The project's name is only needed
            // for the length; a file that cannot be read is Start's to report, not this check's.
            String projectName = "";
            if (projectFile != null) {
                try {
                    projectName = org.fuin.sokar.core.project.ProjectReader.read(projectFile).name();
                } catch (RuntimeException ex) {
                    projectName = "";
                }
            }
            // A container name is taken as the task it names, as Start takes it.
            final String task = projectName.isEmpty() ? taskName
                    : org.fuin.sokar.runtime.ContainerName.taskFrom(projectName, taskName);
            final java.util.Optional<String> badName =
                    org.fuin.sokar.runtime.ContainerName.refusal(projectName, task);
            if (badName.isPresent()) {
                return refused(Outcome.BAD_TASK_NAME, badName.get());
            }
        }

        try (InstalledAgents agents = context.agents()) {

            final TaskLaunch.Choice chosen = TaskLaunch.considerAgent(agents, agentName);
            if (chosen.refusal() != null) {
                return refused(switch (chosen.refusal()) {
                    case UNKNOWN_AGENT -> Outcome.UNKNOWN_AGENT;
                    case SEVERAL_AGENTS -> Outcome.SEVERAL_AGENTS;
                }, chosen.detail());
            }
            final InstalledAgent agent = chosen.agent();
            if (agent == null) {
                return refused(Outcome.NO_AGENT, "no agent is installed, so there is nothing to"
                        + " run; a task can still be started as a shell");
            }

            final SelectedProvider.Choice serving = SelectedProvider.consider(
                    context.providers(), agent.definition(), providerName);
            if (serving.refusal() != null) {
                return new Result(switch (serving.refusal()) {
                    case NO_PROVIDER_CHOSEN -> Outcome.NO_PROVIDER_CHOSEN;
                    case UNKNOWN_PROVIDER -> Outcome.UNKNOWN_PROVIDER;
                    case WRONG_DIALECT -> Outcome.WRONG_DIALECT;
                }, agent.name(), serving.wanted() == null ? "" : serving.wanted(), "",
                        serving.detail());
            }

            final CredentialChoice choice =
                    new CredentialChoice(context, providerName, credentialType, agentName);
            final String provider = serving.selection() == null ? "" : serving.selection().name();

            if (serving.selection() == null || choice.tokenVariable(agent) == null) {
                // Takes no brokered credential, so there is nothing that could be missing.
                return new Result(Outcome.READY, agent.name(), provider, "",
                        "'" + agent.name() + "' needs no brokered credential");
            }

            final String credential = choice.credentialName(agent);
            final var stored = context.readableCredentials();
            if (stored.isEmpty()) {
                return new Result(Outcome.VAULT_LOCKED, agent.name(), provider, credential,
                        "the vault is locked, so whether '" + credential + "' is there cannot be"
                                + " answered; 'sokar vault unlock' at the machine");
            }
            if (!stored.get().containsKey(credential)) {
                return new Result(Outcome.CREDENTIAL_MISSING, agent.name(), provider, credential,
                        "the vault holds no credential for '" + credential + "'");
            }
            final String unusable = choice.unbrokerable(agent);
            if (unusable != null) {
                return new Result(Outcome.CREDENTIAL_UNUSABLE, agent.name(), provider, credential,
                        unusable);
            }
            return new Result(Outcome.READY, agent.name(), provider, credential,
                    "'" + credential + "' is stored and usable");

        } catch (org.fuin.sokar.agent.api.AgentException ex) {
            // An agent that cannot be asked what it is. Not one of the outcomes above, because it
            // is not a choice anybody made - and refusing to answer at all would be worse than
            // saying which agent is broken.
            return refused(Outcome.UNKNOWN_AGENT, ex.getMessage());
        }
    }
}
