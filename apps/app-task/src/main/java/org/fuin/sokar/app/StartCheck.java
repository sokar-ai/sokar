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

        /** No repository was named, and Start never picks one. {@code detail} names the choices. */
        NO_REPOSITORY_CHOSEN,

        /**
         * The repository is reached over ssh at a host this machine never met: a person confirms its key with
         * {@code sokar credentials trust-host} first. {@link Result#host()} names it.
         */
        UNKNOWN_HOST_KEY,

        /** A repository was named and the project has none of that name. */
        UNKNOWN_REPOSITORY,

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
        CREDENTIAL_UNUSABLE,

        /** A credential the project or the run names is for a destination nobody declared here, or the
         *  run points one of the project's elsewhere. {@code credential} names it; {@code detail} says which
         *  named it. */
        UNKNOWN_DESTINATION,

        /** A credential the project or the run names is one a person grants (kind {@code oauth-device} or
         *  {@code oauth-code}) and nobody has granted it yet. {@code credential} names the entry; {@code detail}
         *  gives the command that grants it. Refuses an unattended or agent run; a shell starts with a warning.
         *  A grant that ended at the service is not seen here: it is found when the broker spends it. */
        AUTHORIZATION_NEEDED
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
            String detail, String host) {

        /**
         * Constructor for an answer about anything but a host.
         *
         * @param outcome Why work could not start, or {@link Outcome#READY}.
         * @param agent The agent, or "".
         * @param provider The provider, or "".
         * @param credential The vault key, or "".
         * @param detail Prose for a person.
         */
        public Result(Outcome outcome, String agent, String provider, String credential, String detail) {
            this(outcome, agent, provider, credential, detail, "");
        }

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
            // What to run on that machine to store it, when that is what is missing. The line is
            // built here rather than joined together by a client out of Providers(): it is the
            // same string ProviderInventory already makes, and a second place that builds it is a
            // second place it can drift.
            map.put("storeCommand", outcome == Outcome.CREDENTIAL_MISSING && !credential.isEmpty()
                    ? java.util.List.of("sokar", "vault", "put", credential)
                    : java.util.List.<String>of());
            map.put("detail", detail);
            map.put("host", host);
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
        return check(context, projectFile, taskName, agentName, providerName, credentialType,
                null, true);
    }

    /**
     * Answers whether a run with these choices could start, including the repository.
     * <p>
     * <strong>The repository has to be checked here or the promise breaks.</strong> This check
     * exists so that an answer of READY and a Start that refuses cannot disagree, and starting a
     * task now always names a repository. A check that skipped it would tell an interface to
     * enable its button and let the start fail a second later, which is the exact failure this
     * method was written to remove.
     *
     * @param context Where the agents, providers and vault come from.
     * @param projectFile The project file, or {@code null} not to check one.
     * @param taskName The task name Start would be given, or {@code null} not to check one.
     * @param agentName Agent to run, or {@code null} for the only one installed.
     * @param providerName Provider to route through, or {@code null} for the agent's own default.
     * @param credentialType Overrides the stored credential kind, or {@code null}.
     * @param repository Which repository the task is for, or {@code null} when none was named.
     * @param needsRepository Whether this run would have a gate at all. A task started with no
     *        gate gets an empty directory and works on no repository, so there is nothing for it
     *        to name.
     * @return What was found.
     */
    public static Result check(SokarContext context, @Nullable Path projectFile,
            @Nullable String taskName, @Nullable String agentName, @Nullable String providerName,
            @Nullable String credentialType, @Nullable String repository,
            boolean needsRepository) {

        return check(context, projectFile, taskName, agentName, providerName, credentialType, repository,
                needsRepository, Map.of());
    }

    /**
     * Answers whether a run with these choices could start, including the credentials it names.
     *
     * @param context Where the agents, providers and vault come from.
     * @param projectFile The project file, or {@code null} not to check one.
     * @param taskName The task name Start would be given, or {@code null} not to check one.
     * @param agentName Agent to run, or {@code null} for the only one installed.
     * @param providerName Provider to route through, or {@code null} for the agent's own default.
     * @param credentialType Overrides the stored credential kind, or {@code null}.
     * @param repository Which repository the task is for, or {@code null} when none was named.
     * @param needsRepository Whether this run would have a gate at all.
     * @param credentials The credentials the run adds, a vault entry to its destination.
     * @return What was found.
     */
    public static Result check(SokarContext context, @Nullable Path projectFile,
            @Nullable String taskName, @Nullable String agentName, @Nullable String providerName,
            @Nullable String credentialType, @Nullable String repository,
            boolean needsRepository, Map<String, String> credentials) {

        final Result rest = checkEverythingElse(context, projectFile, taskName, agentName,
                providerName, credentialType);
        final Result chosen = withRepository(withCredentials(context, rest, projectFile, credentials), projectFile,
                repository, needsRepository);
        return needsRepository ? withHost(context, chosen, projectFile, repository) : chosen;
    }

    /**
     * Returns the answer with the credentials the project and the run name applied: each must be for a
     * destination declared here, and a run may not point one of the project's elsewhere.
     *
     * @param context Where destinations and providers are declared.
     * @param rest What everything else answered.
     * @param projectFile The project file, or {@code null}.
     * @param run The credentials the run adds.
     * @return The answer.
     */
    static Result withCredentials(SokarContext context, Result rest, @Nullable Path projectFile,
            Map<String, String> run) {
        // Before the agent's own credential: an undeclared destination refuses a start in every mode, where a
        // missing credential does not stop a shell - an interface told only the second would offer a start
        // that Start then refuses (found by Agent Frontend, 2026-09-29).
        if (rest.outcome() != Outcome.READY && rest.outcome() != Outcome.CREDENTIAL_MISSING
                && rest.outcome() != Outcome.CREDENTIAL_UNUSABLE && rest.outcome() != Outcome.VAULT_LOCKED) {
            return rest;
        }
        final String refused = undeclared(context, projectFile, run);
        if (refused != null) {
            final String credential = refused.substring(0, refused.indexOf('\n'));
            return new Result(Outcome.UNKNOWN_DESTINATION, rest.agent(), rest.provider(), credential,
                    refused.substring(refused.indexOf('\n') + 1));
        }
        if (rest.outcome() != Outcome.READY) {
            return rest;
        }
        final String ungranted = ungranted(context, projectFile, run);
        if (ungranted != null) {
            return new Result(Outcome.AUTHORIZATION_NEEDED, rest.agent(), rest.provider(), ungranted,
                    "nobody has granted '" + ungranted + "' yet; a person authorizes it once with 'sokar vault"
                            + " authorize " + ungranted + "'");
        }
        return rest;
    }

    /**
     * Names the first credential the project or the run names that a person grants and nobody has granted.
     * <p>
     * Asks only whether a grant is there: spending it here could rotate it under the broker.
     *
     * @param context Where the vault is.
     * @param projectFile The project file, or {@code null}.
     * @param run The credentials the run adds.
     * @return The entry's name, or {@code null} when every such credential is granted or the vault is locked.
     */
    static @Nullable String ungranted(SokarContext context, @Nullable Path projectFile, Map<String, String> run) {
        final java.util.Set<String> named = new java.util.LinkedHashSet<>();
        if (projectFile != null) {
            try {
                named.addAll(org.fuin.sokar.core.project.ProjectReader.read(projectFile).credentials().keySet());
            } catch (RuntimeException ex) {
                // An unreadable project file is answered elsewhere.
            }
        }
        named.addAll(run.keySet());
        if (named.isEmpty()) {
            return null;
        }
        final var stored = context.readableCredentials();
        final var grants = context.readableGrants();
        if (stored.isEmpty() || grants.isEmpty()) {
            return null;
        }
        for (final String name : named) {
            final org.fuin.sokar.vault.VaultEntry entry = stored.get().get(name);
            if (entry != null && org.fuin.sokar.supervisor.Grants.isGrant(entry.type())
                    && !grants.get().containsKey(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * Says which credential cannot be had for want of a destination, and why, in the words a person reads.
     *
     * @param context Where destinations and providers are declared.
     * @param projectFile The project file, or {@code null}.
     * @param run The credentials the run adds.
     * @return The credential's name, a newline, and the reason; or {@code null} when every one resolves.
     */
    public static @Nullable String undeclared(SokarContext context, @Nullable Path projectFile, Map<String, String> run) {
        Map<String, String> declared = Map.of();
        if (projectFile != null) {
            try {
                declared = org.fuin.sokar.core.project.ProjectReader.read(projectFile).credentials();
            } catch (RuntimeException ex) {
                declared = Map.of();
            }
        }
        for (final Map.Entry<String, String> added : run.entrySet()) {
            final String already = declared.get(added.getKey());
            if (already != null && !already.equals(added.getValue())) {
                return added.getKey() + "\nthe project already names credential '" + added.getKey() + "' for '"
                        + already + "'; a run adds credentials and cannot point one of the project's elsewhere";
            }
        }
        final Map<String, Destination> destinations = Destination.all(context.paths().xdg().data());
        final Map<String, org.fuin.sokar.agent.api.ProviderDefinition> providers = context.providers();
        final java.util.Map<String, String> all = new java.util.LinkedHashMap<>(declared);
        run.forEach(all::putIfAbsent);
        for (final Map.Entry<String, String> named : all.entrySet()) {
            if (Destination.resolve(named.getValue(), destinations, providers, null) == null) {
                final String who = declared.containsKey(named.getKey()) ? "the project names" : "the run names";
                return named.getKey() + "\n" + who + " credential '" + named.getKey() + "' for '" + named.getValue()
                        + "', and no destination or provider of that name is declared here";
            }
        }
        return null;
    }

    /**
     * Returns the answer with the repository question applied, which is asked last.
     * <p>
     * <strong>Last, although Start refuses on it first.</strong> Nothing else depends on which
     * repository a task is for, so asking it early only buys a refusal that HIDES the others: an
     * interface asking <em>"can work start in this project at all"</em> would have been told to
     * choose a repository and learned nothing about a missing agent, a locked vault or an absent
     * credential. Asked last, {@link Outcome#NO_REPOSITORY_CHOSEN} means <em>"and nothing else is
     * in the way"</em>, which is the sentence a required choice in a dialog needs - the same shape
     * {@link Outcome#SEVERAL_AGENTS} has.
     * <p>
     * The contract still holds: {@link Outcome#READY} is returned only when a repository was named
     * and exists, so a check that says READY and a Start that refuses cannot disagree.
     *
     * @param rest What everything else answered.
     * @param projectFile The project file, or {@code null} when none was given.
     * @param repository What was named, or {@code null}.
     * @param needsRepository Whether this run would have a gate at all.
     * @return The answer.
     */
    /**
     * Returns the answer with the host of the chosen repository's upstream: one reached over ssh that this machine
     * never met is refused, naming it, before anything is made.
     * <p>
     * It went ahead before: ssh refused the workspace's fetch, the task ran without a gate, and the only trace was
     * a line of prose in the start's output. Asked after the repository, so the host is the one the run would use.
     *
     * @param context Where this machine's known hosts are.
     * @param rest What everything else answered.
     * @param projectFile The project file, or {@code null}.
     * @param repository The chosen repository, or {@code null}.
     * @return The answer.
     */
    static Result withHost(SokarContext context, Result rest, @Nullable Path projectFile,
            @Nullable String repository) {
        // With no repository chosen the question is not yet which host: READY says one was chosen and exists.
        if (rest.outcome() != Outcome.READY && (repository == null || repository.isBlank())) {
            return rest;
        }
        final String host = unknownHost(context, projectFile, repository);
        // Before the vault's questions, not after: trusting a host needs no vault, and asked last it hid behind a
        // locked one - a person unlocked only to be asked about the host next. After everything else, which a host
        // does not settle.
        if (host == null || !(rest.outcome() == Outcome.READY || rest.outcome() == Outcome.VAULT_LOCKED
                || rest.outcome() == Outcome.CREDENTIAL_MISSING || rest.outcome() == Outcome.CREDENTIAL_UNUSABLE)) {
            return rest;
        }
        return new Result(Outcome.UNKNOWN_HOST_KEY, rest.agent(), rest.provider(), rest.credential(),
                "this machine has never met " + host + ", where the repository is, so it stops rather than"
                        + " deciding for you. See what it offers with 'sokar credentials trust-host " + host
                        + "', confirm one against what its owner publishes, and start again.", host);
    }

    /**
     * Returns the host of a repository's upstream when it is reached over ssh and this machine never met it.
     *
     * @param context Where this machine's known hosts are.
     * @param projectFile The project file, or {@code null}.
     * @param repository The repository, or {@code null} for the project's own.
     * @return The host, or {@code null} when there is no such question.
     */
    public static @Nullable String unknownHost(SokarContext context, @Nullable Path projectFile,
            @Nullable String repository) {
        if (projectFile == null) {
            return null;
        }
        final String upstream;
        try {
            final org.fuin.sokar.core.project.Project project = org.fuin.sokar.core.project.ProjectReader.read(
                    projectFile);
            final org.fuin.sokar.core.project.Repository chosen = repository == null || repository.isBlank()
                    ? project.ownRepository() : project.repository(repository);
            upstream = chosen == null ? null : chosen.upstream();
        } catch (RuntimeException ex) {
            return null;
        }
        return upstream == null ? null : unknownHostOf(context, upstream);
    }

    /**
     * Returns the host of an address reached over ssh when this machine never met it on that port.
     *
     * @param context Where this machine's known hosts are.
     * @param upstream The address.
     * @return The host, or {@code null} when there is no such question.
     */
    static @Nullable String unknownHostOf(SokarContext context, String upstream) {
        if (!overSsh(upstream)) {
            return null;
        }
        final String said = upstream.strip();
        final String host = !said.contains("://") && said.matches("[^/:@]+:.*") ? said.substring(0, said.indexOf(':'))
                : GitCredentialNames.hostOf(upstream);
        if (host == null) {
            return null;
        }
        // On its own port, as ssh writes it: a line for port 22 says nothing about another one.
        int port = -1;
        if (upstream.strip().startsWith("ssh://")) {
            try {
                port = java.net.URI.create(upstream.strip()).getPort();
            } catch (IllegalArgumentException ex) {
                port = -1;
            }
        }
        final String entry = port > 0 && port != 22 ? "[" + host + "]:" + port : host;
        return HostKeys.known(context, entry) ? null : host;
    }

    private static boolean overSsh(String upstream) {
        final String said = upstream.strip();
        // 'user@host:path' and 'host:path' alike: git reads both as ssh, and the second was taken for a local path.
        return said.startsWith("ssh://") || !said.contains("://") && said.matches("([^/@:]+@)?[^/:@]+:.*");
    }

    static Result withRepository(Result rest, @Nullable Path projectFile,
            @Nullable String repository, boolean needsRepository) {

        if (rest.outcome() != Outcome.READY || projectFile == null || !needsRepository) {
            return rest;
        }
        final org.fuin.sokar.core.project.Project project;
        try {
            project = org.fuin.sokar.core.project.ProjectReader.read(projectFile);
        } catch (RuntimeException ex) {
            // A file that is there and cannot be read is Start's to report, as it is for the task
            // name. Not this check's to turn into an outcome.
            return rest;
        }
        if (repository == null || repository.isBlank()) {
            return new Result(Outcome.NO_REPOSITORY_CHOSEN, rest.agent(), rest.provider(),
                    rest.credential(), "say which repository this task is for. '" + project.name()
                            + "' works in: " + String.join(", ", project.workRepositoryNames()));
        }
        if (OwnRepository.refused(project, repository)) {
            return new Result(Outcome.UNKNOWN_REPOSITORY, rest.agent(), rest.provider(),
                    rest.credential(), OwnRepository.refusal(project));
        }
        if (project.repository(repository) == null) {
            return new Result(Outcome.UNKNOWN_REPOSITORY, rest.agent(), rest.provider(),
                    rest.credential(), "project '" + project.name() + "' has no repository '"
                            + repository + "'. It works in: " + OwnRepository.worksIn(project));
        }
        return rest;
    }

    /**
     * Everything a start needs except which repository it is for.
     *
     * @param context The machine.
     * @param projectFile The project file, or {@code null}.
     * @param taskName The task name, or {@code null}.
     * @param agentName Agent to run, or {@code null}.
     * @param providerName Provider to route through, or {@code null}.
     * @param credentialType Credential kind override, or {@code null}.
     * @return What was found.
     */
    private static Result checkEverythingElse(SokarContext context, @Nullable Path projectFile,
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

            final AgentChoice.Choice chosen = TaskLaunch.considerAgent(agents, agentName);
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
                // The sign-in first, for an agent that has one: a new person told only what is missing went on to the
                // one start that needs no credential, a shell where the agent said "Not logged in".
                return new Result(Outcome.CREDENTIAL_MISSING, agent.name(), provider, credential,
                        "the vault holds no credential for '" + credential + "'"
                                + (agent.definition().loginArguments() == null ? ""
                                        : "; sign in with '" + agent.name() + "' first: 'sokar vault login "
                                                + agent.name() + "'"));
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
            return refused(Outcome.UNKNOWN_AGENT, CliErrors.reason(ex));
        }
    }
}
