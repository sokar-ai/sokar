package org.fuin.sokar.core.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Which keys a project file may hold, and where.
 * <p>
 * <strong>What is certainly a mistake is refused</strong>: a key that belongs in another section, or one spelt
 * within two letters of a key that belongs here. Ignored, either is silently no setting, and the file looks
 * applied. <strong>Any other unknown key is accepted and warned about</strong> (decided by the operator on
 * 2026-09-30): a file written for a later Sokar must still run on this one.
 * <p>
 * Two places are open by design: the names under {@code credentials} and {@code repositories} are the
 * project's own, and what is under {@code mail.transports.<scheme>} is the transport's, handed to it as it is
 * written - the transport refuses what it does not know.
 */
final class ProjectSchema {

    private static final Map<String, Set<String>> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("", Set.of("project", "image", "egress", "limits", "mail", "repositories", "credentials",
                "builds"));
        SECTIONS.put("builds", Set.of("forge", "credential", "api", "logs"));
        SECTIONS.put("project", Set.of("name", "description", "security_class", "upstream",
                "signers"));
        SECTIONS.put("image", Set.of("base_image", "snippet", "snippet_file", "package_sources"));
        SECTIONS.put("egress", Set.of("sets", "domains", "refused"));
        SECTIONS.put("limits", Set.of("memory", "pids", "cpus", "hand_in"));
        SECTIONS.put("mail", Set.of("peers", "transports", "outgoing_filter", Mail.RULES));
        SECTIONS.put("mail.rules", Set.of("project", "room", "others"));
        SECTIONS.put("mail.peers.<peer>", Set.of("address", "trust", "per_day", "mode"));
        SECTIONS.put("repositories.<repository>", Set.of("upstream", "description", "egress", "limits", "issues"));
    }

    /**
     * Settings Sokar read once and reads no more, each with what decides instead: refused by name wherever they
     * stand, since a file that carries one would look as if it still did something.
     */
    private static final Map<String, String> REMOVED = Map.of("unread_work_may_leave",
            "how closely a message is watched is set by 'mail.rules' and a peer's 'mode'.");

    /** The modes a message rule takes, in the order they are offered. */
    private static final List<String> MODES = List.of(Mail.PROMPT, Mail.ALLOW, Mail.DENY, Mail.OFF);

    private static final List<ProjectKey> KEYS = List.of(
            key("", "project", "Who the project is and how far its agents are trusted.", "map", "", true),
            key("", "image", "What a task's container is built from.", "map", "", true),
            key("", "egress", "What a task may reach beyond what its agent needs; nothing else resolves at all.",
                    "map", "", false),
            key("", "limits", "What one task may consume.", "map", "", false),
            key("", "mail", "Whom the project's tasks may write to, and over which transports.", "map", "", false),
            key("", "repositories", "The other repositories the work happens in, each by the name a task start"
                    + " takes; the project's own is always there.", "map", "", false),
            key("", "credentials", "Vault entries every task holds, each named with the destination it is for;"
                    + " only names, never values.", "map", "", false),
            key("", "builds", "An online project's tasks learn what the build of their own push did.", "map", "",
                    false),
            key("builds", "forge", "Which forge runs the build, by the name its installed build reader declares.",
                    "string", "", true),
            key("builds", "credential", "The vault entry holding the forge token; it never enters a task.",
                    "string", "", false),
            key("builds", "api", "The forge API's https:// address; unset is the forge's own public one.", "string",
                    "", false),
            new ProjectKey("builds", "logs", "Which jobs' logs reach the task: 'failure', each failed one, or 'all',"
                    + " every one once the build is finished.", "string", List.of(Builds.FAILURE, Builds.ALL),
                    Builds.FAILURE, false),
            key("project", "name", "The project's name: lower-case letters, digits and hyphens, at most 63"
                    + " characters, and not 'default'.", "string", "", true),
            key("project", "description", "For people: shown wherever the project is listed.", "string", "", false),
            key("project", "signers", "The public keys of the people whose signed commits change this file. A"
                    + " commit signed by a key a machine has pinned that adds or removes one here makes it pin or"
                    + " unpin it; a key never counts for the commit that adds it.", "list", "", false),
            new ProjectKey("project", "security_class", "How far the agent is trusted: offline reaches nothing,"
                    + " guarded pushes to Sokar's gate and holds no credential for the upstream, online pushes to"
                    + " the upstream itself.", "string", List.of("offline", "guarded", "online"), "", true),
            key("project", "upstream", "Where approved work on the project's own repository goes; required for"
                    + " online.", "string", "", false),
            key("image", "base_image", "The image a task's container is built from; any with apt, apk or dnf.",
                    "string", "", true),
            key("image", "snippet", "Extra Containerfile lines, run as root while the image is built.", "string",
                    "", false),
            key("image", "snippet_file", "The same as snippet, in a file of the repository; not both.", "string",
                    "", false),
            key("image", "package_sources", "Where apt fetches from while the image is built.", "list",
                    "[\"http://azure.archive.ubuntu.com/ubuntu/\"]", false),
            key("egress", "sets", "Curated sets of destinations by name; 'sokar shield sets' lists this machine's.",
                    "list", "[]", false),
            key("egress", "domains", "Any other name a task may reach, on ports 80 and 443.", "list", "[]", false),
            key("egress", "refused", "Names that must not resolve, whatever allows them.", "list", "[]", false),
            key("limits", "memory", "The most memory a task may use, as podman takes it; \"none\" opts out.",
                    "string", "8g", false),
            key("limits", "pids", "The most processes a task may run, which is what stops a fork bomb.", "integer",
                    "2048", false),
            key("limits", "cpus", "The most CPUs a task may use, as podman takes it; unset is no limit.", "string",
                    "", false),
            key("limits", "hand_in", "The largest file 'sokar task give' may hand to a running task, such as 64m;"
                    + " fixed when the task starts.", "string", "64m", false),
            key("mail", "peers", "The names tasks may write to, each with where it is reached.", "map", "", false),
            new ProjectKey("mail", "outgoing_filter", "Whether the filter refuses what it finds in a task's outgoing"
                    + " message, the default, or only reports it; a person can still read a refused message and deliver"
                    + " it.", "string", List.of("blocking", "reporting"), "blocking", false),
            key("mail", Mail.RULES, "How closely a message is watched, set once for every task: a mode for the"
                    + " project's own tasks, for its conversation and for everyone else.", "map", "", false),
            new ProjectKey("mail.rules", "project", "The mode for a message to another task of the project.",
                    "string", MODES, Mail.ALLOW, false),
            new ProjectKey("mail.rules", "room", "The mode for a message to the project's conversation: allow where"
                    + " unread work may leave this machine, prompt otherwise.", "string", MODES, "", false),
            new ProjectKey("mail.rules", "others", "The mode for a message to any other peer.", "string", MODES,
                    Mail.DENY, false),
            key("mail", "transports", "Each transport's settings, by its scheme; the transport says what they"
                    + " mean.", "map", "", false),
            key("mail.peers.<peer>", "address", "Where the peer is reached: <transport>:<the transport's own"
                    + " address>.", "string", "", true),
            new ProjectKey("mail.peers.<peer>", "trust", "Whether what arrives from the peer is checked again here:"
                    + " vouched for one of your own machines, external for anybody else.", "string",
                    List.of(Mail.Peer.VOUCHED, Mail.Peer.EXTERNAL), Mail.Peer.EXTERNAL, false),
            key("mail.peers.<peer>", "per_day", "How many messages a day a task may exchange with the peer, each"
                    + " way.", "integer", String.valueOf(Mail.Peer.DEFAULT_PER_DAY), false),
            new ProjectKey("mail.peers.<peer>", "mode", "The mode for a message to this peer, over its class's"
                    + " rule.", "string", MODES, "", false),
            key("repositories.<repository>", "upstream", "Where approved work on this repository goes; without one,"
                    + " it stays on this machine.", "string", "", false),
            key("repositories.<repository>", "description", "For people: shown wherever the repository is listed.",
                    "string", "", false),
            key("repositories.<repository>", "egress", "What a task on this repository may reach in addition to the"
                    + " project's: sets, domains, refused.", "map", "", false),
            key("repositories.<repository>", "limits", "What a task on this repository may consume, replacing the"
                    + " project's key by key: memory, pids, cpus.", "map", "", false),
            key("repositories.<repository>", "issues", "The issue prefixes this repository owns, each one or more"
                    + " capital letters and named by no other repository; a task on it is told them.", "list", "",
                    false));

    private ProjectSchema() {
    }

    private static ProjectKey key(String section, String name, String describe, String type, String defaultValue,
            boolean required) {
        return new ProjectKey(section, name, describe, type, List.of(), defaultValue, required);
    }

    /**
     * Returns every key described, in the order a first project file is written in.
     *
     * @return The keys.
     */
    static List<ProjectKey> keys() {
        return KEYS;
    }

    /**
     * Returns the keys each section may hold, for an editor built from what this Sokar knows.
     *
     * @return Section - {@code ""} for the top level, {@code mail.peers.<peer>} and
     *         {@code repositories.<repository>} for the named ones - to its keys, sorted.
     */
    static Map<String, List<String>> sections() {
        final Map<String, List<String>> sections = new LinkedHashMap<>();
        SECTIONS.forEach((section, keys) -> sections.put(section, keys.stream().sorted().toList()));
        return sections;
    }

    /**
     * Refuses a key the file may not hold where it is.
     *
     * @param root The whole document.
     * @param origin Name used in error messages.
     * @throws ProjectException Naming the first key that is not a setting, and where it belongs.
     */
    static List<String> check(Map<?, ?> root, String origin) {
        final List<String> unknown = new java.util.ArrayList<>();
        keys(root, "", "", origin, unknown);
        for (final String section : List.of("project", "image", "egress", "limits", "mail", "builds")) {
            if (root.get(section) instanceof Map<?, ?> map) {
                keys(map, section, section, origin, unknown);
            }
        }
        if (root.get("mail") instanceof Map<?, ?> mail && mail.get("peers") instanceof Map<?, ?> peers) {
            peers.forEach((name, peer) -> {
                if (peer instanceof Map<?, ?> map) {
                    keys(map, "mail.peers.<peer>", "mail.peers." + name, origin, unknown);
                }
            });
        }
        if (root.get("repositories") instanceof Map<?, ?> repositories) {
            repositories.forEach((name, repository) -> {
                if (repository instanceof Map<?, ?> map) {
                    keys(map, "repositories.<repository>", "repositories." + name, origin, unknown);
                    for (final String inner : List.of("egress", "limits")) {
                        if (map.get(inner) instanceof Map<?, ?> nested) {
                            keys(nested, inner, "repositories." + name + "." + inner, origin, unknown);
                        }
                    }
                }
            });
        }
        return List.copyOf(unknown);
    }

    private static void keys(Map<?, ?> map, String section, String path, String origin, List<String> unknown) {
        final Set<String> known = java.util.Objects.requireNonNull(SECTIONS.get(section), section);
        for (final Object key : map.keySet()) {
            final String name = String.valueOf(key);
            if (known.contains(name)) {
                continue;
            }
            final String where = path.isEmpty() ? name : path + "." + name;
            if (REMOVED.containsKey(name)) {
                throw new ProjectException(origin + ": '" + where + "' is no longer a setting; " + REMOVED.get(name)
                        + " Remove it.");
            }
            final String home = home(name, section);
            final String near = home == null ? nearest(name, known) : null;
            if (home == null && near == null) {
                unknown.add(where);
                continue;
            }
            throw new ProjectException(origin + ": '" + where + "' is not a setting"
                    + (home != null ? "; it belongs under '" + home + ":'" : "; did you mean '" + near + "'?"));
        }
    }

    /**
     * Returns the section a key belongs in, when it is one of another section's.
     *
     * @param name The key.
     * @param not The section it was found in.
     * @return The section, or {@code null}.
     */
    private static @Nullable String home(String name, String not) {
        for (final Map.Entry<String, Set<String>> section : SECTIONS.entrySet()) {
            if (!section.getKey().equals(not) && section.getValue().contains(name)) {
                return section.getKey().isEmpty() ? "the top level" : section.getKey().replace(".<peer>", ".<name>")
                        .replace(".<repository>", ".<name>");
            }
        }
        return null;
    }

    private static @Nullable String nearest(String name, Set<String> known) {
        String best = null;
        int distance = 3;
        for (final String candidate : known) {
            final int each = distance(name, candidate);
            if (each < distance) {
                distance = each;
                best = candidate;
            }
        }
        return best;
    }

    private static int distance(String a, String b) {
        final int[] previous = new int[b.length() + 1];
        final int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1], previous[j]) + 1,
                        previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            System.arraycopy(current, 0, previous, 0, current.length);
        }
        return previous[b.length()];
    }
}
