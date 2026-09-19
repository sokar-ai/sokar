package org.fuin.sokar.app;

import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.jspecify.annotations.Nullable;

/**
 * Who a host thinks this machine is, when it is asked with a particular key.
 * <p>
 * <strong>Only the machine can find this out, and it is usually the whole answer.</strong> A forge
 * that refuses says "repository not found", which is true and tells nobody anything: the key may
 * belong to another account, or be a deploy key for another repository entirely. Asking the host
 * turns half an hour of guessing into a sentence - measured on 2026-09-19, where {@code ssh -T}
 * answered <em>"Hi fuinorg/utils4j!"</em> for a key that could never reach the project being
 * followed.
 * <p>
 * <strong>Asked at two moments</strong>, which is why it is not buried in either: when a fetch has
 * been refused, and when somebody declares a credential and wants to know before they rely on it.
 * The second is the better moment and was Agent Frontend's idea.
 */
public final class ForgeIdentity {

    private ForgeIdentity() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Asks a host who it thinks we are.
     *
     * @param context The machine, for the credential and for running ssh.
     * @param url The destination, in any git form.
     * @param label Names the agent socket.
     * @return What the host said, or {@code null} when it said nothing recognisable, could not be
     *         reached, or the URL is not one ssh opens. Never an error: this is an explanation,
     *         and an explanation that fails explains nothing and breaks nothing.
     */
    public static @Nullable String of(final SokarContext context, final String url,
            final String label) {
        if (GitCredentialNames.kindOf(url) != GitCredentialNames.Kind.KEY) {
            return null;
        }
        final String host = GitCredentialNames.hostOf(url);
        if (host == null) {
            return null;
        }
        final String user = userIn(url);
        try (FollowCredential credential = FollowCredential.open(context, url, label)) {
            final CommandResult asked = context.runner().run(Command.of("ssh",
                    "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-T",
                    (user == null ? "git" : user) + "@" + host)
                    .withEnvironment(credential.environment()));
            // A forge answers a greeting and exits non-zero; that greeting is the whole point.
            return (asked.standardError() + "\n" + asked.standardOutput()).lines()
                    .map(String::strip)
                    .filter(line -> line.startsWith("Hi ") || line.contains("successfully"))
                    .findFirst().orElse(null);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /**
     * Returns the user a git URL names, or {@code null} when it names none.
     * <p>
     * An address with no user makes ssh log in as whoever runs it, which on a forge is never
     * right - and the form is easy to copy from a prefix without seeing the difference.
     *
     * @param url The destination.
     * @return The user, or {@code null}.
     */
    public static @Nullable String userIn(final String url) {
        final String withoutScheme = url.replaceFirst("^[a-zA-Z0-9+.-]+://", "");
        final int at = withoutScheme.indexOf('@');
        if (at < 0) {
            return null;
        }
        final String user = withoutScheme.substring(0, at);
        return user.isEmpty() || user.contains("/") ? null : user;
    }
}
