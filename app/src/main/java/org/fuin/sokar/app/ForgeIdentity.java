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
    /**
     * What a host answered when asked who we are.
     *
     * @param said The greeting, or what it refused with, or "".
     * @param refused Whether it turned the key away outright. A key registered nowhere looks
     *        perfectly good until the first fetch, and silence about it reads exactly like
     *        "nothing to report" - which is the one thing it must not read like.
     */
    public record Answer(String said, boolean refused) { }

    /**
     * Asks a host who it thinks we are, and whether it will have us at all.
     *
     * @param context The machine.
     * @param url The destination.
     * @param label Names the agent socket.
     * @return What it said, or {@code null} when nothing could be asked.
     */
    public static @Nullable Answer ask(final SokarContext context, final String url,
            final String label) {
        return ask(context, url, label, null);
    }

    /**
     * Asks a host about a particular credential, recorded or not.
     *
     * @param context The machine.
     * @param url The destination.
     * @param label Names the agent socket.
     * @param offered The credential to ask with, or {@code null} for whatever is recorded. A dry
     *        run has to ask with the key somebody is considering: asking with the recorded one
     *        would answer about a different key, or about none, and either reads as an answer
     *        about theirs.
     * @return What it said, or {@code null} when nothing could be asked.
     */
    public static @Nullable Answer ask(final SokarContext context, final String url,
            final String label,
            final org.fuin.sokar.core.credential.@Nullable Credential offered) {
        if (GitCredentialNames.kindOf(url) != GitCredentialNames.Kind.KEY) {
            return null;
        }
        final String host = GitCredentialNames.hostOf(url);
        if (host == null) {
            return null;
        }
        final String user = userIn(url);
        try (FollowCredential credential =
                FollowCredential.open(context, url, label, "git", offered)) {
            final java.util.List<String> command = new java.util.ArrayList<>(java.util.List.of(
                    "ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
                    // The same host-key policy a fetch uses, or the FIRST question asked of a
                    // host this machine has never met fails on the host key and says nothing
                    // about the credential - which is exactly what happened when this was tried
                    // with a plain 'ssh': the answer was silence, and silence reads as "nothing
                    // to report".
                    "-o", "StrictHostKeyChecking=accept-new",
                    "-o", "UserKnownHostsFile=" + FollowCredential.knownHostsFile(context)));
            if (offered != null
                    && offered.source() == org.fuin.sokar.core.credential.Credential.Source.FILE) {
                // A key in a file is named to ssh directly. The lease says so in GIT_SSH_COMMAND,
                // which git reads and ssh does not - so asking with the lease's environment alone
                // asked about no key at all.
                command.addAll(java.util.List.of("-i", offered.id(),
                        "-o", "IdentitiesOnly=yes"));
            }
            command.addAll(java.util.List.of("-T", (user == null ? "git" : user) + "@" + host));
            final CommandResult asked = context.runner().run(Command.of(command)
                    .withEnvironment(credential.environment()));
            final String all = asked.standardError() + "\n" + asked.standardOutput();
            final String greeting = all.lines().map(String::strip)
                    .filter(line -> line.startsWith("Hi ") || line.contains("successfully"))
                    .findFirst().orElse(null);
            if (greeting != null) {
                return new Answer(greeting, false);
            }
            final String denied = all.lines().map(String::strip)
                    .filter(line -> line.contains("Permission denied")
                            || line.contains("publickey"))
                    .findFirst().orElse(null);
            // Anything else - an unreachable host, a host key that changed - is not an answer
            // about the credential and must not be reported as one.
            return denied == null ? null : new Answer(denied, true);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    public static @Nullable String of(final SokarContext context, final String url,
            final String label) {
        final Answer answer = ask(context, url, label);
        return answer == null || answer.refused() ? null : answer.said();
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
