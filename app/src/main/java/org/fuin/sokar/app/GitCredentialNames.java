package org.fuin.sokar.app;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Which vault entry a git URL reaches for, and what to call it.
 * <p>
 * <strong>The scheme decides the kind.</strong> An {@code https://} URL is opened by a token and an
 * {@code ssh://} or {@code git@host:} URL by a key; git itself asks for one or the other and never
 * both, so this is not a preference to configure but a fact to read off the URL.
 * <p>
 * <strong>The host decides which one.</strong> A company GitLab and a public forge are two
 * different credentials, and a machine that offered the same token to both would hand a
 * company's token to whatever host a project file names. So an entry may be per host, with a
 * plain one as the fallback for a machine that has only one:
 *
 * <pre>
 *   git.ssh.github.com     a key for that host        git.token.github.com
 *   git.ssh                the key for anywhere else  git.token
 *   ssh.default            what a task already pushes with, still read for ssh
 * </pre>
 *
 * The names are the whole interface: {@code sokar vault put git.token.github.com} is what a person
 * types and what an interface shows them to type, and nothing else has to be configured for a
 * project that names that host.
 */
public final class GitCredentialNames {

    /** What a task's ssh-agent has always read, kept as the last fallback for a key. */
    public static final String LEGACY_SSH = "ssh.default";

    private static final String SSH = "git.ssh";

    private static final String TOKEN = "git.token";

    private GitCredentialNames() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** What kind of credential a URL is opened by. */
    public enum Kind {

        /** An ssh key, served through an agent. */
        KEY,

        /** A token, handed to git through a credential helper. */
        TOKEN,

        /** Neither: a local path, or a scheme nothing here authenticates. */
        NONE
    }

    /**
     * Says what kind of credential a URL needs.
     *
     * @param url The repository.
     * @return The kind, never {@code null}.
     */
    public static Kind kindOf(final @Nullable String url) {
        if (url == null) {
            return Kind.NONE;
        }
        final String trimmed = url.strip();
        if (trimmed.startsWith("ssh://") || trimmed.matches("^[^/@]+@[^/:]+:.*")) {
            return Kind.KEY;
        }
        if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            return Kind.TOKEN;
        }
        return Kind.NONE;
    }

    /**
     * Returns the host a URL names, which is what an entry may be keyed by.
     *
     * @param url The repository.
     * @return The host, or {@code null} when the URL names none.
     */
    public static @Nullable String hostOf(final @Nullable String url) {
        if (url == null) {
            return null;
        }
        final String trimmed = url.strip();
        final int scheme = trimmed.indexOf("://");
        if (scheme >= 0) {
            final String rest = trimmed.substring(scheme + 3);
            final int at = rest.indexOf('@');
            final String hostAndPath = at >= 0 ? rest.substring(at + 1) : rest;
            final int end = firstOf(hostAndPath, '/', ':');
            final String host = end < 0 ? hostAndPath : hostAndPath.substring(0, end);
            return host.isEmpty() ? null : host;
        }
        if (trimmed.matches("^[^/@]+@[^/:]+:.*")) {
            return trimmed.substring(trimmed.indexOf('@') + 1, trimmed.indexOf(':'));
        }
        return null;
    }

    /**
     * Returns the entries to look in for a URL, most specific first.
     *
     * @param url The repository.
     * @return Names to try in order, empty when the URL needs no credential.
     */
    public static List<String> candidatesFor(final @Nullable String url) {
        final String host = hostOf(url);
        return switch (kindOf(url)) {
            case KEY -> host == null ? List.of(SSH, LEGACY_SSH)
                    : List.of(SSH + "." + host, SSH, LEGACY_SSH);
            case TOKEN -> host == null ? List.of(TOKEN) : List.of(TOKEN + "." + host, TOKEN);
            case NONE -> List.of();
        };
    }

    /**
     * Returns what to tell somebody to type when nothing is stored for a URL.
     *
     * @param url The repository.
     * @return The command, naming the host when there is one.
     */
    public static String storeCommandFor(final @Nullable String url) {
        final List<String> candidates = candidatesFor(url);
        if (candidates.isEmpty()) {
            return "";
        }
        return "sokar vault put " + candidates.get(0)
                + (kindOf(url) == Kind.KEY ? " < <the private key file>" : "");
    }

    /**
     * Tells whether a name is one of these rather than a provider's.
     *
     * @param name An entry name.
     * @return {@code true} for a git credential.
     */
    public static boolean isOne(final @Nullable String name) {
        return name != null
                && (name.equals(LEGACY_SSH) || name.equals(SSH) || name.equals(TOKEN)
                        || name.startsWith(SSH + ".") || name.startsWith(TOKEN + "."));
    }

    private static int firstOf(final String text, final char one, final char other) {
        final int first = text.indexOf(one);
        final int second = text.indexOf(other);
        if (first < 0) {
            return second;
        }
        if (second < 0) {
            return first;
        }
        return Math.min(first, second);
    }
}
