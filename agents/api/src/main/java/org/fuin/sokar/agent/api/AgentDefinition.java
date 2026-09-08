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
 *        telemetry and crash reporting. Declared rather than merely absent so that a test can
 *        tell a policy choice from an oversight, and so an operator can see what is refused.
 * @param version Version of the agent CLI this definition installs, or {@code null} if it
 *        installs nothing.
 * @param artifacts Files the image build fetches, each pinned and verified.
 * @param installAsRoot Container build fragments run as root, possibly empty.
 * @param installAsAgent Container build fragments run as the agent user, possibly empty.
 * @param packaged Directories shipped in the agent's own package, copied into the image.
 */
public record AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
        HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
        Map<String, String> tokenEnvironment, @Nullable AgentProvider provider,
        List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
        List<InstallArtifact> artifacts,
        List<String> installAsRoot, List<String> installAsAgent,
        List<PackagedTree> packaged, @Nullable String configDirectory,
        @Nullable List<String> loginArguments) {

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
                installAsRoot, installAsAgent, packaged, configDirectory, null);
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
        if (!artifacts.isEmpty() && (version == null || version.isBlank())) {
            // An artifact list without a version cannot answer "which version ran?" after the
            // fact, which is half the reason for declaring the artifacts at all.
            throw new AgentException("Agent '" + name
                    + "' installs artifacts but declares no version");
        }
        tokenEnvironment = Map.copyOf(tokenEnvironment);
        allowedDomains = List.copyOf(allowedDomains);
        refusedDomains = List.copyOf(refusedDomains);
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
}
