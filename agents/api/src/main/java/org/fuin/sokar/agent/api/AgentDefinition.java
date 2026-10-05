package org.fuin.sokar.agent.api;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything about an agent that is data rather than behavior.
 * <p>
 * Read from the {@code agent.yaml} inside the agent's own module. What cannot be expressed here is
 * expressed by overriding a method on {@link Agent}, in that same module - never by a branch
 * elsewhere that tests the agent's name.
 *
 * @param name Short name, and the key an operator types.
 * @param label Human-readable label.
 * @param binary Executable the agent is invoked as inside the container.
 * @param gitIdentity Author its commits are attributed to.
 * @param headless How its command line is assembled.
 * @param supportsResume Whether a previous session can be continued.
 * @param resumeFlag Flag that continues a session, or {@code null}.
 * @param tokenEnvironment Credential type to environment variable, with {@code _default} as the
 *        fallback key.
 * @param provider Which providers the agent can drive and how it must be pointed at one, or
 *        {@code null} if the agent cannot be redirected and must be given its credential
 *        directly.
 * @param allowedDomains Domains the agent needs to resolve and reach.
 * @param refusedDomains Domains the agent is known to ask for and is deliberately not given -
 *        telemetry and crash reporting. The task's resolver answers NXDOMAIN for each, even under
 *        an allowed parent, so what an operator is told is refused is what is refused. Declared
 *        rather than merely absent so that a test can tell a policy choice from an oversight.
 * @param version Version of the agent CLI this definition installs, or {@code null} if it
 *        installs nothing.
 * @param artifacts Files the image build fetches, each pinned and verified.
 * @param installAsRoot Container build fragments run as root, possibly empty.
 * @param installAsAgent Container build fragments run as the agent user, possibly empty.
 * @param packaged Directories shipped in the agent's own package, copied into the image.
 * @param configDirectory Where the agent keeps its credentials, or {@code null}.
 * @param loginArguments What runs the agent's own login, or {@code null} if it does not say.
 * @param loginDocumentation Where that login is described, or {@code null}. Declared because an
 *        agent's login is its own: some open a browser, some print a link and a code, and only
 *        the second kind works on a machine somebody reaches over ssh. A person about to run one
 *        on a remote machine should be able to read what it will do before it does it, and the
 *        agent is the only thing that knows where that is written.
 * @param sandboxedArguments Arguments that turn off the agent's own permission prompts.
 *        <p>
 *        <strong>Sokar decides whether, the manifest says only how.</strong> Inside a task the
 *        answer is always yes: the box is the whole point, and an agent stopping to ask whether
 *        it may run a command is asking about a restriction that was already lifted. So there is
 *        no flag and no setting to turn this on - it is on, and an agent that declares nothing
 *        here simply has no prompts to turn off.
 * @param ready What the agent shows once it has reached work, attached, or {@code null} when it has
 *        no stable text for that - and then a check of it says it cannot tell rather than passing.
 * @param waiting What waiting for a person looks like in its own output, or {@code null} when it does
 *        not say - and then Sokar says it cannot tell whether the agent waits, never that it does not.
 * @param sessionIds Where it names the session it runs, or {@code null} when it does not - and then a task
 *        that comes back starts a fresh session and says so, rather than claiming it continued.
 * @param instructionArguments How the agent takes standing instructions from a file, with {@value #FILE} where the
 *        file goes, or empty when it declares no way. Sokar gives it the file that says how the task's mailbox works.
 * @param atRest What its screen shows at rest at its prompt, or {@code null}: then it is never woken by typing.
 */
public record AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
        HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
        Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
        List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
        List<InstallArtifact> artifacts,
        List<String> installAsRoot, List<String> installAsAgent,
        List<PackagedTree> packaged, @Nullable String configDirectory,
        @Nullable List<String> loginArguments, @Nullable String loginDocumentation,
        List<String> sandboxedArguments, @Nullable ReadyMarker ready, @Nullable Waiting waiting,
        @Nullable SessionIds sessionIds, List<String> instructionArguments, @Nullable AtRest atRest) {

    /** Where the file goes in {@link #instructionArguments()}. */
    public static final String FILE = "{file}";

    /**
     * Constructor for an agent that declares no way to take standing instructions.
     *
     * @param name Name the binary calls itself by.
     * @param label Human-readable name.
     * @param binary Command it runs inside the container.
     * @param gitIdentity What its commits are signed with.
     * @param headless Flags for a non-interactive run.
     * @param supportsResume Whether it can continue a previous session.
     * @param resumeFlag Flag that continues one, or {@code null}.
     * @param tokenEnvironment Variable its credential is read from, by kind.
     * @param provider How it reaches a model provider, or {@code null}.
     * @param allowedDomains Hosts it needs.
     * @param refusedDomains Hosts it asked for and is not given.
     * @param version Build it pins, or {@code null}.
     * @param artifacts What it fetches when its image is built.
     * @param installAsRoot Image lines that run as root.
     * @param installAsAgent Image lines that run as the agent.
     * @param packaged Trees copied into the image.
     * @param configDirectory Where it keeps its credentials, or {@code null}.
     * @param loginArguments How it logs in, or {@code null}.
     * @param loginDocumentation Where that is described, or {@code null}.
     * @param sandboxedArguments What turns its own permission prompts off.
     * @param ready What it shows once it reached work, or {@code null}.
     * @param waiting What waiting for a person looks like, or {@code null}.
     * @param sessionIds Where it names its session, or {@code null}.
     */
    public AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
            HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
            Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
            List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
            List<InstallArtifact> artifacts, List<String> installAsRoot, List<String> installAsAgent,
            List<PackagedTree> packaged, @Nullable String configDirectory,
            @Nullable List<String> loginArguments, @Nullable String loginDocumentation,
            List<String> sandboxedArguments, @Nullable ReadyMarker ready, @Nullable Waiting waiting,
            @Nullable SessionIds sessionIds) {
        this(name, label, binary, gitIdentity, headless, supportsResume, resumeFlag, tokenEnvironment, provider,
                allowedDomains, refusedDomains, version, artifacts, installAsRoot, installAsAgent, packaged,
                configDirectory, loginArguments, loginDocumentation, sandboxedArguments, ready, waiting, sessionIds,
                List.of(), null);
    }

    /**
     * Constructor for an agent that declares no screen at rest.
     *
     * @param name Name the binary calls itself by.
     * @param label Human-readable name.
     * @param binary Command it runs inside the container.
     * @param gitIdentity What its commits are signed with.
     * @param headless Flags for a non-interactive run.
     * @param supportsResume Whether it can continue a previous session.
     * @param resumeFlag Flag that continues one, or {@code null}.
     * @param tokenEnvironment Variable its credential is read from, by kind.
     * @param provider How it reaches a model provider, or {@code null}.
     * @param allowedDomains Hosts it needs.
     * @param refusedDomains Hosts it asked for and is not given.
     * @param version Build it pins, or {@code null}.
     * @param artifacts What it fetches when its image is built.
     * @param installAsRoot Image lines that run as root.
     * @param installAsAgent Image lines that run as the agent.
     * @param packaged Trees copied into the image.
     * @param configDirectory Where it keeps its credentials, or {@code null}.
     * @param loginArguments How it logs in, or {@code null}.
     * @param loginDocumentation Where that is described, or {@code null}.
     * @param sandboxedArguments What turns its own permission prompts off.
     * @param ready What it shows once it reached work, or {@code null}.
     * @param waiting What waiting for a person looks like, or {@code null}.
     * @param sessionIds Where it names its session, or {@code null}.
     * @param instructionArguments How it takes standing instructions from a file.
     */
    public AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
            HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
            Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
            List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
            List<InstallArtifact> artifacts, List<String> installAsRoot, List<String> installAsAgent,
            List<PackagedTree> packaged, @Nullable String configDirectory,
            @Nullable List<String> loginArguments, @Nullable String loginDocumentation,
            List<String> sandboxedArguments, @Nullable ReadyMarker ready, @Nullable Waiting waiting,
            @Nullable SessionIds sessionIds, List<String> instructionArguments) {
        this(name, label, binary, gitIdentity, headless, supportsResume, resumeFlag, tokenEnvironment, provider,
                allowedDomains, refusedDomains, version, artifacts, installAsRoot, installAsAgent, packaged,
                configDirectory, loginArguments, loginDocumentation, sandboxedArguments, ready, waiting, sessionIds,
                instructionArguments, null);
    }

    /**
     * Returns a command that starts this agent with standing instructions read from a file.
     * <p>
     * Directly behind the binary and what turns its prompts off, where {@link #sandboxedCommand()} ends, so they
     * cannot land after a positional prompt - on both ways of starting it, as that command is. An agent that builds its
     * unattended command its own way gets them directly behind its binary.
     *
     * @param command How it is started, beginning with its binary.
     * @param file Where the instructions are, inside the task.
     * @return The command with the declared arguments in it, or unchanged when the agent declares none.
     */
    public List<String> instructed(List<String> command, String file) {
        if (instructionArguments.isEmpty() || command.isEmpty()) {
            return command;
        }
        final List<String> prefix = sandboxedCommand();
        final int at = command.size() >= prefix.size() && command.subList(0, prefix.size()).equals(prefix)
                ? prefix.size() : 1;
        final List<String> instructed = new java.util.ArrayList<>(command.subList(0, at));
        instructionArguments.forEach(argument -> instructed.add(argument.replace(FILE, file)));
        instructed.addAll(command.subList(at, command.size()));
        return List.copyOf(instructed);
    }

    /**
     * Returns the agent's binary followed by whatever turns its own permission prompts off.
     * <p>
     * <strong>Both ways of starting an agent go through here</strong>, so an attached run and an
     * unattended one cannot drift apart on the one question a person would only notice in the
     * attached case - and only after the agent had already stopped to ask.
     *
     * @return Command and arguments, never empty.
     */
    public List<String> sandboxedCommand() {
        final List<String> command = new java.util.ArrayList<>();
        command.add(binary);
        command.addAll(sandboxedArguments);
        return List.copyOf(command);
    }

    /**
     * Returns the command an attended task starts the agent with: {@link #sandboxedCommand()}, and the
     * model when one was asked for.
     * <p>
     * The model goes by the same declared flag as in an unattended run. An attended start used to accept
     * {@code --model} and drop it, so the agent answered on its own default - measured by Agent Smith
     * on 2026-09-29, with two agents' headers naming models nobody had asked for.
     *
     * @param model The model to ask for, or {@code null} for the agent's own.
     * @return Command and arguments, never empty.
     * @throws AgentException If a model was asked for and the agent declares no flag to take one.
     */
    public List<String> attendedCommand(@Nullable String model) {
        if (model == null) {
            return sandboxedCommand();
        }
        if (headless.modelFlag() == null) {
            throw new AgentException("Agent '" + name + "' does not take a model");
        }
        final List<String> command = new java.util.ArrayList<>(sandboxedCommand());
        command.add(headless.modelFlag());
        command.add(model);
        return List.copyOf(command);
    }

    /**
     * Constructor for an agent that does not say how to log in.
     * <p>
     * Most do not, and it is not a defect: an agent whose credential is an API key has nothing to
     * log in to.
     *
     * @param name Name the binary calls itself by.
     * @param label Human-readable name.
     * @param binary Command it runs inside the container.
     * @param gitIdentity What its commits are signed with.
     * @param headless Flags for a non-interactive run.
     * @param supportsResume Whether it can continue a previous session.
     * @param resumeFlag Flag that continues one, or {@code null}.
     * @param tokenEnvironment Variable its credential is read from, by kind.
     * @param provider How it reaches a model provider, or {@code null}.
     * @param allowedDomains Hosts it needs.
     * @param refusedDomains Hosts it asked for and is not given.
     * @param version Build it pins, or {@code null}.
     * @param artifacts What it fetches when its image is built.
     * @param installAsRoot Image lines that run as root.
     * @param installAsAgent Image lines that run as the agent.
     * @param packaged Trees copied into the image.
     * @param configDirectory Where it keeps its credentials, or {@code null}.
     */
    public AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
            HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
            Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
            List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
            List<InstallArtifact> artifacts, List<String> installAsRoot,
            List<String> installAsAgent, List<PackagedTree> packaged,
            @Nullable String configDirectory) {
        this(name, label, binary, gitIdentity, headless, supportsResume, resumeFlag,
                tokenEnvironment, provider, allowedDomains, refusedDomains, version, artifacts,
                installAsRoot, installAsAgent, packaged, configDirectory, null, null,
                List.of(), null);
    }

    /**
     * Constructor for an agent that declares no ready marker.
     *
     * @param name Short name.
     * @param label Human-readable label.
     * @param binary Executable name.
     * @param gitIdentity Commit author.
     * @param headless Command-line shape.
     * @param supportsResume Whether sessions can be continued.
     * @param resumeFlag Flag continuing a session, or {@code null}.
     * @param tokenEnvironment Credential type to environment variable.
     * @param provider Which providers it drives, or {@code null}.
     * @param allowedDomains Domains the agent needs.
     * @param refusedDomains Domains it asks for and is deliberately denied.
     * @param version Version of the agent CLI installed.
     * @param artifacts Files the image build fetches.
     * @param installAsRoot Build fragments run as root.
     * @param installAsAgent Build fragments run as the agent user.
     * @param packaged Trees copied into the image.
     * @param configDirectory Where it keeps its credentials, or {@code null}.
     * @param loginArguments What runs its login, or {@code null}.
     * @param loginDocumentation Where that login is described, or {@code null}.
     * @param sandboxedArguments What turns its own permission prompts off.
     */
    public AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
            HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
            Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
            List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
            List<InstallArtifact> artifacts, List<String> installAsRoot, List<String> installAsAgent,
            List<PackagedTree> packaged, @Nullable String configDirectory,
            @Nullable List<String> loginArguments, @Nullable String loginDocumentation,
            List<String> sandboxedArguments) {
        this(name, label, binary, gitIdentity, headless, supportsResume, resumeFlag, tokenEnvironment, provider,
                allowedDomains, refusedDomains, version, artifacts, installAsRoot, installAsAgent, packaged,
                configDirectory, loginArguments, loginDocumentation, sandboxedArguments, null);
    }

    /**
     * Constructor for an agent that does not say where it names its sessions.
     *
     * @param name Short name.
     * @param label Human-readable label.
     * @param binary Executable inside the container.
     * @param gitIdentity Commit author.
     * @param headless Command-line shape.
     * @param supportsResume Whether sessions can be continued.
     * @param resumeFlag Flag continuing a session, or {@code null}.
     * @param tokenEnvironment Credential type to environment variable.
     * @param provider Which providers it drives, or {@code null}.
     * @param allowedDomains Domains the agent needs.
     * @param refusedDomains Domains it asks for and is deliberately denied.
     * @param version Version of the agent CLI installed.
     * @param artifacts Files the image build fetches.
     * @param installAsRoot Build fragments run as root.
     * @param installAsAgent Build fragments run as the agent user.
     * @param packaged Trees copied into the image.
     * @param configDirectory Where it keeps its credentials, or {@code null}.
     * @param loginArguments What runs its login, or {@code null}.
     * @param loginDocumentation Where that login is described, or {@code null}.
     * @param sandboxedArguments What turns its own permission prompts off.
     * @param ready What it shows once at work, or {@code null}.
     * @param waiting What waiting for a person looks like, or {@code null}.
     */
    public AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
            HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
            Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
            List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
            List<InstallArtifact> artifacts, List<String> installAsRoot, List<String> installAsAgent,
            List<PackagedTree> packaged, @Nullable String configDirectory,
            @Nullable List<String> loginArguments, @Nullable String loginDocumentation,
            List<String> sandboxedArguments, @Nullable ReadyMarker ready, @Nullable Waiting waiting) {
        this(name, label, binary, gitIdentity, headless, supportsResume, resumeFlag, tokenEnvironment, provider,
                allowedDomains, refusedDomains, version, artifacts, installAsRoot, installAsAgent, packaged,
                configDirectory, loginArguments, loginDocumentation, sandboxedArguments, ready, waiting, null);
    }

    /**
     * Constructor for an agent that declares nothing about waiting for a person.
     *
     * @param name Short name.
     * @param label Human-readable label.
     * @param binary Executable inside the container.
     * @param gitIdentity Commit author.
     * @param headless Command-line shape.
     * @param supportsResume Whether sessions can be continued.
     * @param resumeFlag Flag continuing a session, or {@code null}.
     * @param tokenEnvironment Credential type to environment variable.
     * @param provider Which providers it drives, or {@code null}.
     * @param allowedDomains Domains the agent needs.
     * @param refusedDomains Domains it asks for and is deliberately denied.
     * @param version Version of the agent CLI installed.
     * @param artifacts Files the image build fetches.
     * @param installAsRoot Build fragments run as root.
     * @param installAsAgent Build fragments run as the agent user.
     * @param packaged Trees copied into the image.
     * @param configDirectory Where it keeps its credentials, or {@code null}.
     * @param loginArguments What runs its login, or {@code null}.
     * @param loginDocumentation Where that login is described, or {@code null}.
     * @param sandboxedArguments What turns its own permission prompts off.
     * @param ready What it shows once at work, or {@code null}.
     */
    public AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
            HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
            Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
            List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
            List<InstallArtifact> artifacts, List<String> installAsRoot, List<String> installAsAgent,
            List<PackagedTree> packaged, @Nullable String configDirectory,
            @Nullable List<String> loginArguments, @Nullable String loginDocumentation,
            List<String> sandboxedArguments, @Nullable ReadyMarker ready) {
        this(name, label, binary, gitIdentity, headless, supportsResume, resumeFlag, tokenEnvironment, provider,
                allowedDomains, refusedDomains, version, artifacts, installAsRoot, installAsAgent, packaged,
                configDirectory, loginArguments, loginDocumentation, sandboxedArguments, ready, null, null);
    }

    /**
     * Constructor for an agent that keeps no credential of its own on the host.
     *
     * @param name Short name.
     * @param label Human-readable label.
     * @param binary Executable inside the container.
     * @param gitIdentity Identity commits are made with.
     * @param headless Flags for an unattended run.
     * @param supportsResume Whether it can continue a session.
     * @param resumeFlag Flag that resumes, or {@code null}.
     * @param tokenEnvironment Token variable by credential kind.
     * @param provider Which providers it drives, or {@code null}.
     * @param allowedDomains Domains it needs.
     * @param refusedDomains Domains it asks for and is denied.
     * @param version Version of the tool it installs, or {@code null}.
     * @param artifacts What the image build fetches.
     * @param installAsRoot Image lines run as root.
     * @param installAsAgent Image lines run as the agent user.
     */
    public AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
            HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
            Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
            List<String> allowedDomains,
            List<String> refusedDomains, @Nullable String version,
            List<InstallArtifact> artifacts, List<String> installAsRoot,
            List<String> installAsAgent) {
        this(name, label, binary, gitIdentity, headless, supportsResume, resumeFlag,
                tokenEnvironment, provider, allowedDomains, refusedDomains,
                version, artifacts, installAsRoot, installAsAgent, List.of(), null);
    }

    /** Key in {@code tokenEnvironment} used when no credential type matches. */
    public static final String DEFAULT_TOKEN_KEY = "_default";

    /**
     * Constructor with all data.
     *
     * @param name Short name.
     * @param label Human-readable label.
     * @param binary Executable name.
     * @param gitIdentity Commit author.
     * @param headless Command-line shape.
     * @param supportsResume Whether sessions can be continued.
     * @param resumeFlag Flag continuing a session, or {@code null}.
     * @param tokenEnvironment Credential type to environment variable.
     * @param provider Which providers it drives, or {@code null}.
     * @param allowedDomains Domains the agent needs.
     * @param refusedDomains Domains it asks for and is deliberately denied.
     * @param version Version of the agent CLI installed.
     * @param artifacts Files the image build fetches.
     * @param installAsRoot Build fragments run as root.
     * @param installAsAgent Build fragments run as the agent user.
     */
    public AgentDefinition {
        if (!name.matches("[a-z0-9][a-z0-9-]{0,30}")) {
            // The name reaches image labels, container names and file paths, all stricter than
            // YAML. Rejecting it here beats a confusing failure three layers down.
            throw new AgentException("Invalid agent name '" + name
                    + "', expected lower-case letters, digits and hyphens");
        }
        if (binary.isBlank()) {
            throw new AgentException("Agent '" + name + "' needs a binary");
        }
        if (supportsResume && (resumeFlag == null || resumeFlag.isBlank())) {
            throw new AgentException("Agent '" + name
                    + "' says it supports resume but names no flag for it");
        }
        if (sessionIds != null && !supportsResume) {
            // An id nothing can continue would be recorded to be shown and never used.
            throw new AgentException("Agent '" + name
                    + "' says where its session id is but does not support resume");
        }
        if (!artifacts.isEmpty() && (version == null || version.isBlank())) {
            // An artifact list without a version cannot answer "which version ran?" after the
            // fact, which is half the reason for declaring the artifacts at all.
            throw new AgentException("Agent '" + name
                    + "' installs artifacts but declares no version");
        }
        tokenEnvironment = Map.copyOf(tokenEnvironment);
        allowedDomains = List.copyOf(allowedDomains);
        refusedDomains = List.copyOf(refusedDomains);
        // Checked with the same grammar a project's own egress domains are checked with, and for
        // the same reason: every one of these is written verbatim into a dnsmasq 'server=' or
        // 'nftset=' line and into the firewall's allow sets. A value carrying a newline, a slash
        // or a comment character is not a bad host name there - it is additional configuration,
        // in the file that decides what a container may reach.
        //
        // The project side has had this from the beginning; this side had only a check that the
        // allow and refuse lists did not overlap. An agent's answer arrives from a program, and a
        // program that can widen its own egress is what the shield exists to make impossible.
        //
        // The grammar is repeated rather than shared because this module depends on nothing of
        // Sokar's but the wire types. Two copies of a small pattern is the cheaper mistake.
        for (final String domain : allowedDomains) {
            requireHostName(name, domain);
        }
        for (final String domain : refusedDomains) {
            requireHostName(name, domain);
        }
        for (final String domain : refusedDomains) {
            if (allowedDomains.contains(domain)) {
                // Both lists reaching the firewall would make the ruleset depend on which one is
                // consulted first. Refusing the definition is the only unambiguous answer.
                throw new AgentException("Agent '" + name + "' both allows and refuses '"
                        + domain + "'");
            }
        }
        artifacts = List.copyOf(artifacts);
        installAsRoot = List.copyOf(installAsRoot);
        installAsAgent = List.copyOf(installAsAgent);
    }

    /**
     * Returns the artifacts this agent installs without a digest.
     *
     * @return Unverifiable artifacts, empty when everything is pinned.
     */
    public List<InstallArtifact> unverifiedArtifacts() {
        return InstallScript.unverified(artifacts);
    }

    /**
     * Returns the environment variable a credential of the given type belongs in.
     * <p>
     * The agent's own answer only. Most agents no longer give one, because the variable a
     * provider's credential goes in belongs to the provider - this is the override for an agent
     * that reads something else.
     *
     * @param credentialType Credential type, for example {@code oauth}.
     * @return Variable name, or {@code null} if the agent names none.
     */
    @Nullable
    public String tokenVariable(String credentialType) {
        final String specific = tokenEnvironment.get(credentialType);
        return specific != null ? specific : tokenEnvironment.get(DEFAULT_TOKEN_KEY);
    }

    /**
     * Returns the variable a credential of the given type belongs in, falling back to the
     * provider's own answer.
     *
     * @param credentialType Credential type.
     * @param serving Provider serving the task.
     * @return Variable name, or {@code null} when neither names one.
     */
    @Nullable
    public String tokenVariable(String credentialType, ProviderDefinition serving) {
        final String own = tokenVariable(credentialType);
        return own != null ? own : serving.tokenVariable(credentialType);
    }

    /** A host name, and nothing that could be read as configuration around one. */
    private static final java.util.regex.Pattern HOST_NAME =
            java.util.regex.Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?"
                    + "(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    /**
     * Refuses anything that is not plainly a host name.
     *
     * @param agent Whose definition it is, for the message.
     * @param domain The value.
     */
    private static void requireHostName(String agent, String domain) {
        if (domain.length() > 253 || !HOST_NAME.matcher(domain).matches()) {
            throw new AgentException("Agent '" + agent + "' declares '" + domain
                    + "', which is not a host name. Only host names reach the resolver and the"
                    + " firewall, because everything else there is configuration.");
        }
    }
}
