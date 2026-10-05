package org.fuin.sokar.app;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Tells whether two addresses name the same repository, whichever of git's spellings each uses.
 * <p>
 * {@code git@github.com:you/app.git}, {@code ssh://git@github.com/you/app} and {@code https://github.com/you/app}
 * are one repository. A checkout's {@code origin} is spelt however the person cloned it, and a followed project's
 * {@code upstream} however its author wrote it; comparing the strings would put the same repository in two projects.
 */
final class RepositoryAddress {

    private RepositoryAddress() {
    }

    /**
     * Returns whether two addresses name the same repository.
     *
     * @param one An address.
     * @param other Another.
     * @return {@code true} when host and path agree, ignoring scheme, user, a trailing {@code .git} and the host's case.
     */
    static boolean same(final @Nullable String one, final @Nullable String other) {
        final String left = key(one);
        return left != null && left.equals(key(other));
    }

    /**
     * Returns what a repository is called, for a name nobody gave: the last part of its path.
     *
     * @param address Its address.
     * @return A repository name, lowercased, with anything a name cannot hold made a dash.
     */
    static String nameOf(final String address) {
        String path = address.strip();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(".git")) {
            path = path.substring(0, path.length() - ".git".length());
        }
        final int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        final String last = path.substring(cut + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-")
                .replaceAll("^-+", "");
        return last.isEmpty() ? "repository" : last.length() > 63 ? last.substring(0, 63) : last;
    }

    /**
     * Returns host and path, lowercased host, no user, no scheme, no {@code .git}; a local path as it is.
     *
     * @param address The address.
     * @return The key, or {@code null} for none.
     */
    static @Nullable String key(final @Nullable String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        final String trimmed = address.strip();
        final String host = GitCredentialNames.hostOf(trimmed);
        String path;
        if (host == null) {
            // A path on this machine, or file://.
            path = trimmed.startsWith("file://") ? trimmed.substring("file://".length()) : trimmed;
            return trim(path);
        }
        final int scheme = trimmed.indexOf("://");
        if (scheme >= 0) {
            final String rest = trimmed.substring(scheme + 3);
            final int slash = rest.indexOf('/');
            path = slash < 0 ? "" : rest.substring(slash + 1);
        } else {
            path = trimmed.substring(trimmed.indexOf(':') + 1);
        }
        return host.toLowerCase(Locale.ROOT) + "/" + trim(path);
    }

    private static String trim(final String path) {
        String trimmed = path;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith(".git") ? trimmed.substring(0, trimmed.length() - ".git".length()) : trimmed;
    }

    /** An http(s) address with something before an '@' in its authority: a user, and often a token with it. */
    private static final java.util.regex.Pattern CARRIES_CREDENTIAL =
            java.util.regex.Pattern.compile("^(https?://)[^/@]+@(.*)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Returns an address without the credential it may carry.
     * <p>
     * {@code https://user:token@host/owner/repo.git} is what cloning with a token leaves as a checkout's {@code origin}.
     * Kept as it is, it would be written into what Sokar keeps and printed in what it says - a secret in a file and in
     * scrollback. The user of an ssh address ({@code git@host:...}) is not a secret and stays.
     *
     * @param address The address.
     * @return It without the user information of an http(s) address; otherwise unchanged.
     */
    static String withoutCredential(final String address) {
        final java.util.regex.Matcher found = CARRIES_CREDENTIAL.matcher(address.strip());
        return found.matches() ? found.group(1) + found.group(2) : address.strip();
    }
}