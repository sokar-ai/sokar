package org.fuin.sokar.core.credential;

import org.jspecify.annotations.Nullable;

/**
 * One credential this machine may use to reach somewhere, described without its secret.
 * <p>
 * <strong>Said, not guessed.</strong> What kind of credential a destination wants cannot be read
 * off its URL: {@code https://} may be a token or a username and password, and inferring it from
 * the scheme worked only as long as git was the only caller. So the kind is part of the record.
 * <p>
 * <strong>The secret is not here.</strong> This is the readable half - what exists, for what, of
 * what kind, under which name - and it is readable with the vault shut. That is the difference
 * between <em>"a credential for this host is configured, unlock the vault"</em> and <em>"nothing
 * is configured, store one"</em>, which a machine could not tell apart while the only record of a
 * credential was the credential itself.
 *
 * @param id What the secret is called: a vault entry, a file, or an environment variable,
 *        depending on {@code source}.
 * @param kind What the destination asks for.
 * @param match The URLs this covers, as a prefix of the normalised URL - {@code ssh://github.com}
 *        or {@code https://gitlab.example/acme/}. The longest match wins, so a record for one
 *        group sits beside the one for the rest of a forge.
 * @param user The username, for {@code BASIC} and for a token that needs one. {@code null} lets
 *        the caller use its own default.
 * @param purpose What it may be used for - {@code git}, {@code registry}, or {@code any}. A token
 *        that may push packages has no business authenticating a clone unless somebody said so.
 * @param source Where the secret is read from.
 * @param expires When an {@code OAUTH} token stops working, RFC 3339, or {@code null} when it
 *        does not expire or nothing recorded it. What turns a 401 from the destination into a
 *        sentence this machine can say first.
 */
public record Credential(String id, Credential.Kind kind, String match, @Nullable String user,
        String purpose, Credential.Source source, @Nullable String expires) {

    /**
     * Constructor for a credential that does not expire.
     *
     * @param id What the secret is called.
     * @param kind What the destination asks for.
     * @param match The URLs this covers.
     * @param user The username, or {@code null}.
     * @param purpose What it may be used for.
     * @param source Where the secret is read from.
     */
    public Credential(String id, Credential.Kind kind, String match, @Nullable String user,
            String purpose, Credential.Source source) {
        this(id, kind, match, user, purpose, source, null);
    }

    /**
     * Tells whether this is a token that has run out.
     *
     * @param now The moment to judge by.
     * @return {@code true} when it expired before then.
     */
    public boolean expiredAt(final java.time.Instant now) {
        if (expires == null) {
            return false;
        }
        try {
            return java.time.Instant.parse(expires).isBefore(now);
        } catch (final java.time.format.DateTimeParseException ex) {
            // An unreadable date is not an expired credential. Refusing on it would lock somebody
            // out over a typo in a field that is only an optimisation.
            return false;
        }
    }

    /** Any purpose, for a credential somebody did not want to pin to one. */
    public static final String ANY = "any";

    /** What a destination asks for. */
    public enum Kind {

        /** An ssh key, offered through an agent or named to ssh directly. */
        SSH_KEY,

        /** A bearer token or personal access token. */
        TOKEN,

        /** A username and a password. */
        BASIC,

        /**
         * A token obtained by an OAuth flow, and kept until it expires.
         * <p>
         * <strong>At the moment of use it is a token</strong> - an https destination is handed the
         * access token exactly as it is handed a personal one, so the lease is the same. What is
         * different is everything around it: an access token expires, a refresh token renews it,
         * and something has to own that flow.
         * <p>
         * <strong>What this does today, said plainly:</strong> the stored access token is used,
         * and when it is known to have expired the machine refuses by name and says what to run
         * to renew it - rather than letting the destination answer with a 401 that reads like a
         * revoked account. Renewing without a person is a flow with a client, endpoints and a
         * token written back, and it is not built. It is named here so the file format does not
         * have to change when it is: a record that says {@code oauth} today keeps its meaning.
         */
        OAUTH
    }

    /** Where the secret is read from when it is needed. */
    public enum Source {

        /**
         * This machine's vault, under {@code id}. Encrypted at rest and shut when the machine is
         * not in use, which is the only source that can say that.
         */
        VAULT,

        /**
         * A file on this machine, at {@code id}. For somebody working on their own computer who
         * has a key in {@code ~/.ssh} and no wish to keep a second copy of it - a real case, and
         * one that used to force either a copy into the vault or no protection at all.
         * <p>
         * <strong>It is as protected as the file is.</strong> Nothing here encrypts it and nothing
         * shuts it; the machine says so wherever this credential is shown, in the way an
         * unverified follow says so.
         */
        FILE,

        /**
         * An environment variable named {@code id}, read from the environment of whatever runs
         * Sokar. For a token a person already exports, and for a CI runner that has one.
         * <p>
         * As protected as that environment is, and reported the same way.
         */
        ENVIRONMENT,

        /**
         * The ssh agent the account already runs. Nothing is read at all: the command inherits
         * {@code SSH_AUTH_SOCK} and whoever has already added their keys decides. The plainest
         * answer to <em>"use my own keys"</em>, and the one that keeps no copy anywhere.
         */
        AGENT
    }

    /**
     * Tells whether this credential is kept where the machine can protect it.
     *
     * @return {@code true} only for the vault.
     */
    public boolean protectedHere() {
        return source == Source.VAULT;
    }

    /**
     * Tells whether this record covers a URL.
     *
     * @param normalised The URL as {@link CredentialRegistry#normalise(String)} writes it.
     * @return {@code true} when {@code match} is it, or it continues {@code match} at a boundary: a {@code /},
     *         {@code :}, {@code ?} or {@code #}, or one {@code match} ends with.
     */
    public boolean covers(final String normalised) {
        // At a boundary, not as a plain prefix: 'https://github.com' covered 'https://github.com.evil.example', and
        // '/org' '/org-evil'.
        if (!normalised.startsWith(match)) {
            return false;
        }
        if (normalised.length() == match.length() || match.endsWith("/") || match.endsWith(":")) {
            return true;
        }
        return "/:?#".indexOf(normalised.charAt(match.length())) >= 0;
    }

    /**
     * Tells whether this record may be used for a purpose.
     *
     * @param asked What the caller is doing, such as {@code git}.
     * @return {@code true} when it matches or the record is for any purpose.
     */
    public boolean serves(final String asked) {
        return ANY.equals(purpose) || purpose.equals(asked);
    }
}
