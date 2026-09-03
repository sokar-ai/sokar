package org.fuin.sokar.agent.api;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Everything about an agent that is data rather than behaviour.
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
 * @param baseUrlEnvironment Variable naming the API endpoint, or {@code null}.
 * @param allowedDomains Domains the agent needs to resolve and reach.
 * @param refusedDomains Domains the agent is known to ask for and is deliberately not given -
 *        telemetry and crash reporting. Declared rather than merely absent so that a test can
 *        tell a policy choice from an oversight, and so an operator can see what is refused.
 * @param version Version of the agent CLI this definition installs, or {@code null} if it
 *        installs nothing.
 * @param artifacts Files the image build fetches, each pinned and verified.
 * @param installAsRoot Container build fragments run as root, possibly empty.
 * @param installAsAgent Container build fragments run as the agent user, possibly empty.
 */
public record AgentDefinition(String name, String label, String binary, GitIdentity gitIdentity,
        HeadlessFlags headless, boolean supportsResume, @Nullable String resumeFlag,
        Map<String, String> tokenEnvironment, @Nullable String baseUrlEnvironment,
        List<String> allowedDomains, List<String> refusedDomains, @Nullable String version,
        List<InstallArtifact> artifacts,
        List<String> installAsRoot, List<String> installAsAgent) {

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
     * @param baseUrlEnvironment Variable naming the API endpoint, or {@code null}.
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
     *
     * @param credentialType Credential type, for example {@code oauth}.
     * @return Variable name, or {@code null} if the agent takes no token.
     */
    @Nullable
    public String tokenVariable(String credentialType) {
        final String specific = tokenEnvironment.get(credentialType);
        return specific != null ? specific : tokenEnvironment.get(DEFAULT_TOKEN_KEY);
    }
}
