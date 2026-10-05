package org.fuin.sokar.agent.impl.stub;

import org.fuin.sokar.agent.api.AgentMain;
import org.fuin.sokar.agent.api.YamlAgent;

/**
 * The agent the acceptance suite drives.
 * <p>
 * It exists so the suite keeps working once the real agents live in their own repositories.
 * Nothing around it is faked: a real image is built, a real container runs, and the firewall and
 * credential proxy are the ones Sokar ships. Only the tool inside is ours - a short script the
 * definition writes into the image, which asks for one name it is granted and one it is refused,
 * and redeems its task token through the proxy.
 * <p>
 * That is a stronger test than a vendor CLI for the parts the core owns, because those reaches
 * are deliberate rather than a side effect of whatever the vendor shipped that month. What it
 * cannot tell you is whether a real CLI honors a socket or stays inside its declared domains -
 * that belongs to the agent's own repository.
 * <p>
 * One override, and only the one every agent with a login has: where its login leaves the credential.
 * The agent API reads that in code, not data. Everything else is {@code stub.yaml}, which is the point: if
 * this agent needed behavior Sokar could not express as data, the suite would be testing the exception
 * rather than the rule.
 */
public final class StubAgent extends YamlAgent {

    /** Where {@code stub.yaml} writes the tool; {@code binary} in the definition names it. */
    static final String TARGET = "/usr/local/bin/sokar-stub-cli";

    /**
     * Constructor.
     */
    public StubAgent() {
        super("stub");
    }

    /**
     * Entry point of the {@code sokar-agent-stub} binary.
     *
     * @param args Command line arguments.
     */
    public static void main(String[] args) {
        AgentMain.run(new StubAgent(), args);
    }

    /**
     * Reads what {@code sokar-stub-cli login} writes: an {@code oauth} token in a JSON file with what renews it
     * beside it - the shape a subscription login leaves - so that the type a real login stores, and the
     * attributes it hands over, are measured too and not only an API key.
     *
     * @return The extractor.
     */
    @Override
    public org.fuin.sokar.agent.api.CredentialExtractor credentialExtractor() {
        final org.fuin.sokar.agent.api.CredentialExtractor token = field("accessToken");
        return directory -> token.extract(directory).map(found -> {
            final java.util.Map<String, String> attributes = new java.util.LinkedHashMap<>();
            for (final String name : java.util.List.of(org.fuin.sokar.agent.api.Credential.REFRESH_TOKEN,
                    org.fuin.sokar.agent.api.Credential.EXPIRES_AT, org.fuin.sokar.agent.api.Credential.TOKEN_URL,
                    org.fuin.sokar.agent.api.Credential.CLIENT_ID)) {
                field(name).extract(directory).ifPresent(value -> attributes.put(name, value.secret()));
            }
            return new org.fuin.sokar.agent.api.Credential(found.type(), found.secret(), attributes);
        });
    }

    private static org.fuin.sokar.agent.api.CredentialExtractor field(final String name) {
        return new org.fuin.sokar.agent.api.JsonFieldExtractor(".credentials.json", "stubOauth." + name, "oauth");
    }

    /**
     * Reads the end of the stub's run: its last act is one call to its provider through the broker, and the answer it
     * printed says whether the provider refused it.
     *
     * @param line One line of its output.
     * @return How its run ended, or {@code null} for a line that is not the provider's answer.
     */
    @Override
    public org.fuin.sokar.agent.api.@org.jspecify.annotations.Nullable AgentEnd ended(String line) {
        if (!line.startsWith("{")) {
            return null;
        }
        final Object parsed;
        try {
            parsed = org.fuin.sokar.wire.Json.parse(line);
        } catch (RuntimeException ex) {
            return null;
        }
        if (!(parsed instanceof java.util.Map<?, ?> answer) || !(answer.get("type") instanceof String type)) {
            return null;
        }
        if (type.equals("error")) {
            final String message = answer.get("error") instanceof java.util.Map<?, ?> error
                    && error.get("message") instanceof String text ? text : "the provider refused";
            return new org.fuin.sokar.agent.api.AgentEnd(false, message, org.fuin.sokar.agent.api.AgentEnd.PROVIDER, null);
        }
        return type.equals("message")
                ? new org.fuin.sokar.agent.api.AgentEnd(true, "", org.fuin.sokar.agent.api.AgentEnd.AGENT, null) : null;
    }
}
