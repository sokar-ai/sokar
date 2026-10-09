package org.fuin.sokar.agent.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Reads an {@code agent.yaml} into an {@link AgentDefinition}.
 * <p>
 * Loaded as plain maps and mapped by hand, for the same two reasons as the project reader: binding
 * onto records would need reflection metadata in a native image, and would let a definition name
 * any class on the classpath.
 */
public final class AgentDefinitionReader {

    private AgentDefinitionReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a definition from a classpath resource.
     *
     * @param loader Loader of the agent's own module.
     * @param resource Resource path, for example {@code /agent/claude.yaml}.
     * @return The definition.
     * @throws AgentException If the resource is missing or unusable.
     */
    public static AgentDefinition fromResource(Class<?> loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new AgentException("No agent definition at " + resource
                        + " in " + loader.getName());
            }
            return read(new InputStreamReader(in, StandardCharsets.UTF_8), resource);
        } catch (IOException ex) {
            throw new AgentException("Cannot read " + resource, ex);
        }
    }

    /**
     * Reads a definition.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @return The definition.
     * @throws AgentException If the content does not describe a usable agent.
     */
    public static AgentDefinition read(Reader reader, String origin) {

        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);

        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new AgentException("Cannot parse " + origin + ": " + ex.getMessage(), ex);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new AgentException(origin + " is empty or is not a YAML mapping");
        }

        final Map<?, ?> git = section(root, "git_identity", origin);
        final Map<?, ?> headless = section(root, "headless", origin);
        final Map<?, ?> session = optionalSection(root, "session");
        final Map<?, ?> provider = optionalSection(root, "provider");
        final Map<?, ?> install = optionalSection(root, "install");

        final boolean supportsResume = flag(session, "supports_resume");

        return new AgentDefinition(
                required(root, "name", origin),
                text(root.get("label"), required(root, "name", origin)),
                required(root, "binary", origin),
                new GitIdentity(required(git, "name", origin), required(git, "email", origin)),
                new HeadlessFlags(
                        // Optional: an empty flag is how a definition says the prompt is
                        // positional, which several agents are.
                        text(optional(headless, "prompt_flag")),
                        optional(headless, "model_flag"),
                        optional(headless, "max_turns_flag"),
                        optional(headless, "verbose_flag"),
                        strings(headless.get("output_format_flags"))),
                supportsResume,
                optional(session, "resume_flag"),
                map(provider.get("token_env")),
                agentProvider(provider, origin),
                strings(root.get("allowed_domains")),
                strings(root.get("refused_domains")),
                optional(install, "version"),
                artifacts(install.get("artifacts"), origin),
                strings(install.get("as_root")),
                strings(install.get("as_agent")),
                packaged(install.get("packaged"), origin),
                optional(root, "config_dir"),
                // How this agent logs in, as arguments to its own binary. Null when the section
                // is absent, which is most agents: one whose credential is an API key has nothing
                // to log in to, and Sokar must not guess a verb - hardcoding one agent's would be
                // wrong for every other.
                //
                // An EMPTY list is not the same as absent. An agent whose login is "just run me"
                // declares the section with no arguments, and reading that as "cannot log in"
                // would refuse the commonest shape there is.
                root.get("login") == null ? null
                        : strings(optionalSection(root, "login").get("arguments")),
                // Where that login is described. Optional even when the section is there: an
                // agent may have a login and no page about it, and a missing link is better than
                // one somebody invented.
                root.get("login") == null ? null
                        : optional(optionalSection(root, "login"), "documentation"),
                // What this agent needs to be told so that it stops asking permission for things
                // the container already prevents. Declared because the flag is the agent's own
                // and Sokar must not guess it - but never conditional: inside a task the answer
                // is always yes, which is what the box is for. An agent that needs nothing says
                // nothing and gets nothing added.
                strings(optionalSection(root, "sandboxed").get("arguments")),
                ready(session, origin),
                waiting(session, origin),
                sessionIds(session, origin),
                // How it takes standing instructions from a file: the agent's own flag, which Sokar must not guess.
                // Absent is the common case, and gets nothing added.
                strings(optionalSection(root, "instructions").get("arguments")),
                // What its screen shows at rest, so a message that names it may wake it; absent, it is never typed into.
                session.get("at_rest") instanceof Map<?, ?> rest
                        ? new AtRest(strings(rest.get("shows")), strings(rest.get("lacks"))) : null,
                // The OAuth apps it signs in to providers with, which 'sokar vault authorize' grants for it.
                grants(optionalSection(root, "login").get("grants"), origin));
    }

    /**
     * Reads {@code login.grants}: per provider, the client id by host and whose app it is.
     *
     * @param value The section, or {@code null}.
     * @param origin Where it was read from, for a refusal.
     * @return The grants, in the order declared.
     */
    private static List<AgentGrant> grants(final @Nullable Object value, final String origin) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Map<?, ?> byProvider)) {
            throw new AgentException(origin + ": login.grants must be a mapping of provider to its grant");
        }
        final List<AgentGrant> grants = new java.util.ArrayList<>();
        byProvider.forEach((provider, grant) -> {
            if (!(grant instanceof Map<?, ?> section) || !(section.get("hosts") instanceof Map<?, ?> hosts)) {
                throw new AgentException(origin + ": the grant for '" + provider + "' needs 'hosts', the client id by"
                        + " host");
            }
            final Map<String, String> ids = new java.util.LinkedHashMap<>();
            hosts.forEach((host, id) -> ids.put(String.valueOf(host), String.valueOf(id)));
            grants.add(new AgentGrant(String.valueOf(provider), ids, optional(section, "owner")));
        });
        return List.copyOf(grants);
    }

    /**
     * Reads where the agent names its session: {@code session.session_id}.
     *
     * @param session The {@code session} block, empty when there is none.
     * @param origin Name used in error messages.
     * @return The declaration, or {@code null} when none is made.
     */
    private static @Nullable SessionIds sessionIds(Map<?, ?> session, String origin) {
        final Object declared = session.get("session_id");
        if (declared == null) {
            return null;
        }
        if (!(declared instanceof Map<?, ?> ids)) {
            throw new AgentException(origin + ": 'session.session_id' is a mapping");
        }
        only(ids, List.of("record", "key", "directory", "suffix"), origin, "session.session_id");
        try {
            return new SessionIds(map(ids.get("record")), optional(ids, "key"), optional(ids, "directory"),
                    optional(ids, "suffix"));
        } catch (AgentException ex) {
            throw new AgentException(origin + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads what waiting for a person looks like in this agent's output: {@code session.waiting}.
     * <p>
     * Absent is an answer of its own, as for the ready marker: an agent that cannot tell declares
     * nothing, and Sokar then says it cannot tell rather than that the agent is not waiting. Every key is
     * named, so a misspelt one is refused rather than read as a rule that never matches.
     *
     * @param session The {@code session} block, empty when there is none.
     * @param origin Name used in error messages.
     * @return The declaration, or {@code null} when none is made.
     */
    private static @Nullable Waiting waiting(Map<?, ?> session, String origin) {
        final Object declared = session.get("waiting");
        if (declared == null) {
            return null;
        }
        if (!(declared instanceof Map<?, ?> waiting)) {
            throw new AgentException(origin + ": 'session.waiting' is a mapping");
        }
        only(waiting, List.of("screen", "not_its_screen", "last_message"), origin, "session.waiting");
        try {
            final Map<?, ?> last = optionalSection(waiting, "last_message");
            Waiting.LastMessage lastMessage = null;
            if (waiting.get("last_message") != null) {
                only(last, List.of("record", "text"), origin, "session.waiting.last_message");
                lastMessage = new Waiting.LastMessage(map(last.get("record")), required(last, "text", origin));
            }
            return new Waiting(rules(waiting.get("screen"), origin), rules(waiting.get("not_its_screen"), origin),
                    lastMessage);
        } catch (AgentException ex) {
            // A record's own refusal does not know which file it came from; say it.
            final String said = String.valueOf(ex.getMessage());
            throw said.startsWith(origin) ? ex : new AgentException(origin + ": " + said, ex);
        }
    }

    private static List<Waiting.Rule> rules(@Nullable Object declared, String origin) {
        if (declared == null) {
            return List.of();
        }
        if (!(declared instanceof List<?> list)) {
            throw new AgentException(origin + ": waiting rules are a list");
        }
        final List<Waiting.Rule> rules = new ArrayList<>();
        for (final Object each : list) {
            if (!(each instanceof Map<?, ?> rule)) {
                throw new AgentException(origin + ": a waiting rule is a mapping, not '" + each + "'");
            }
            only(rule, List.of("contains", "in_last_lines", "for"), origin, "a waiting rule");
            final Object lines = rule.get("in_last_lines");
            if (lines != null && !(lines instanceof Integer)) {
                throw new AgentException(origin + ": 'in_last_lines' is a number of lines, not '" + lines + "'");
            }
            rules.add(new Waiting.Rule(required(rule, "contains", origin), (Integer) lines, optional(rule, "for")));
        }
        return rules;
    }

    private static void only(Map<?, ?> section, List<String> keys, String origin, String where) {
        for (final Object key : section.keySet()) {
            if (!keys.contains(String.valueOf(key))) {
                throw new AgentException(origin + ": " + where + " takes " + keys + ", not '" + key + "'");
            }
        }
    }

    /**
     * Reads what the agent shows once it has reached work: {@code session.ready_marker}, and how long
     * that may take, {@code session.ready_within_seconds}.
     * <p>
     * Absent is an answer of its own: an agent whose ready screen has no stable text declares nothing,
     * and a check of it then says it cannot tell rather than passing.
     *
     * @param session The {@code session} block, empty when there is none.
     * @param origin Name used in error messages.
     * @return The marker, or {@code null} when none is declared.
     */
    private static @Nullable ReadyMarker ready(Map<?, ?> session, String origin) {
        final String text = optional(session, "ready_marker");
        final Object within = session.get("ready_within_seconds");
        if (text == null || text.isBlank()) {
            if (within != null) {
                throw new AgentException(origin + ": 'ready_within_seconds' bounds a 'ready_marker', and none is declared");
            }
            return null;
        }
        if (within != null && !(within instanceof Integer)) {
            throw new AgentException(origin + ": 'ready_within_seconds' is a number of seconds, not '" + within + "'");
        }
        return new ReadyMarker(text, (Integer) within);
    }

    /**
     * Reads the {@code provider} block.
     * <p>
     * Absent for an agent that cannot be redirected at a proxy. That is a real case rather than
     * an error - it means the agent has to be given its credential directly, which is a decision
     * the operator makes with {@code --credential-type direct}, not something this reader can fix.
     * <p>
     * What is <em>not</em> here any more is the provider's own half: upstream, header, prefix and
     * path. Those are declared once, in the provider's own file, and an agent that restated them
     * would be free to disagree with the agent next to it about the same provider.
     *
     * @param provider The provider block.
     * @param origin Where the definition came from, for error messages.
     * @return What the agent says about providers, or {@code null} when it declares none.
     */
    @Nullable
    private static AgentProvider agentProvider(Map<?, ?> provider, String origin) {
        if (provider.isEmpty()) {
            return null;
        }
        final String dialect = optional(provider, "dialect");
        if (dialect == null) {
            // A block with only 'token_env' describes an agent that takes its credential
            // directly and is not brokered at all - a real case, not a mistake. But a block
            // that says how to point the agent somewhere and then omits the dialect is a
            // mistake, and a silent one, so it is named.
            for (final String key : List.of("endpoint", "socket_env", "base_url_env", "default")) {
                if (provider.get(key) != null) {
                    throw new AgentException(origin + ": 'provider." + key + "' is set, so the"
                            + " block must also name the 'dialect' the agent speaks, or 'native'");
                }
            }
            return null;
        }
        return new AgentProvider(dialect, optional(provider, "default"),
                ProviderRoute.Endpoint.of(optional(provider, "endpoint"), origin),
                optional(provider, "socket_env"), optional(provider, "base_url_env"));
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static List<PackagedTree> packaged(@Nullable Object value, String origin) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new AgentException(origin + ": 'install.packaged' must be a list");
        }
        final List<PackagedTree> result = new ArrayList<>();
        for (final Object element : list) {
            if (!(element instanceof Map<?, ?> entry)) {
                throw new AgentException(origin + ": each 'install.packaged' entry must be a map");
            }
            result.add(new PackagedTree(required(entry, "source", origin),
                    required(entry, "target", origin)));
        }
        return List.copyOf(result);
    }

    private static List<InstallArtifact> artifacts(@Nullable Object value, String origin) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> list)) {
            throw new AgentException(origin + ": 'install.artifacts' must be a list");
        }
        final List<InstallArtifact> result = new ArrayList<>();
        for (final Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                throw new AgentException(origin + ": each install artifact must be a mapping");
            }
            result.add(new InstallArtifact(
                    required(map, "url", origin),
                    optional(map, "sha256"),
                    required(map, "target", origin),
                    mode(map.get("mode"), origin),
                    Boolean.TRUE.equals(map.get("unverified")),
                    optional(map, "reason"),
                    optional(map, "license")));
        }
        return List.copyOf(result);
    }

    /**
     * Reads an artifact's mode, which must be written as a string.
     * <p>
     * {@code mode: 0644} is the number 420 to YAML, and the file was installed as 0420; {@code 0755} became 493 and was
     * refused in words that did not say why.
     */
    private static String mode(final @Nullable Object mode, final String origin) {
        if (mode == null) {
            return "0755";
        }
        if (!(mode instanceof String said)) {
            throw new AgentException(origin + ": an install artifact's mode must be a string, as in mode: '0644' -"
                    + " written as a number YAML reads it in octal, and " + mode + " is not what was meant");
        }
        return said;
    }

    private static Map<?, ?> section(Map<?, ?> root, String name, String origin) {
        final Object value = root.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw new AgentException(origin + " has no '" + name + "' section");
        }
        return map;
    }

    private static Map<?, ?> optionalSection(Map<?, ?> root, String name) {
        return root.get(name) instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String required(Map<?, ?> section, String key, String origin) {
        final Object value = section.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new AgentException(origin + ": '" + key + "' is required");
        }
        return String.valueOf(value);
    }

    @Nullable
    private static String optional(Map<?, ?> section, String key) {
        final Object value = section.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String text(@Nullable Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean flag(Map<?, ?> section, String key) {
        return Boolean.TRUE.equals(section.get(key));
    }

    private static List<String> strings(@Nullable Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof String single) {
            // A YAML block scalar is one string holding several lines. Splitting it here means an
            // install fragment can be written as a readable block rather than a list of lines.
            return single.lines().filter(line -> !line.isBlank()).map(String::stripTrailing).toList();
        }
        if (!(value instanceof List<?> list)) {
            throw new AgentException("Expected a list but found " + value.getClass().getSimpleName());
        }
        final List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return List.copyOf(result);
    }

    private static Map<String, String> map(@Nullable Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> source)) {
            throw new AgentException("Expected a mapping but found "
                    + value.getClass().getSimpleName());
        }
        final Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> result.put(String.valueOf(key), String.valueOf(item)));
        return Map.copyOf(result);
    }
}
