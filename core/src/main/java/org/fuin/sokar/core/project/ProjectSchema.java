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
        SECTIONS.put("", Set.of("project", "image", "egress", "limits", "mail", "repositories", "credentials"));
        SECTIONS.put("project", Set.of("name", "description", "security_class", "upstream", "unread_work_may_leave"));
        SECTIONS.put("image", Set.of("base_image", "snippet", "snippet_file", "package_sources"));
        SECTIONS.put("egress", Set.of("sets", "domains", "refused"));
        SECTIONS.put("limits", Set.of("memory", "pids", "cpus"));
        SECTIONS.put("mail", Set.of("peers", "transports"));
        SECTIONS.put("mail.peers.<peer>", Set.of("address", "trust", "per_day"));
        SECTIONS.put("repositories.<repository>", Set.of("upstream", "description", "egress", "limits"));
    }

    private ProjectSchema() {
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
        for (final String section : List.of("project", "image", "egress", "limits", "mail")) {
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
