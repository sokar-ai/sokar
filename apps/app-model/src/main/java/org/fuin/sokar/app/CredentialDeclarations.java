package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.fuin.sokar.core.credential.Credential;
import org.fuin.sokar.core.credential.CredentialRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Writing down what this machine connects out with, and saying whether it would work.
 * <p>
 * <strong>An interface may write these.</strong> Nothing here is secret - a kind, a destination, a
 * username, and where the value lives - so the record can be made over the socket while the value
 * is still typed at the machine. That split is what makes a wizard possible without ever carrying
 * a secret over the wire.
 */
public final class CredentialDeclarations {

    private final SokarContext context;

    /**
     * Constructor.
     *
     * @param context Where the file and the vault are.
     */
    public CredentialDeclarations(final SokarContext context) {
        this.context = context;
    }

    /** Why a destination cannot be reached, or that it can. */
    public enum Outcome {

        /** A credential is declared and its value is there. */
        READY,

        /** Nothing is declared for this destination and no name implies one. */
        NO_CREDENTIAL,

        /** Something is declared in the vault, and the vault is shut. */
        VAULT_LOCKED,

        /** A record exists and its value does not: the file is gone, the variable unset. */
        MISSING_VALUE,

        /** An OAuth token whose moment has passed. */
        EXPIRED,

        /**
         * There is no vault on this machine at all. Told apart from a shut one and from a value
         * that was never stored, because it is a different thing to do first: somebody has to
         * make the vault before anything can be put in it, and a wizard that sent them to
         * {@code vault put} would fail at the last step with a message about a file.
         */
        NO_VAULT,

        /**
         * The host turned this key away. Not "nothing to say" - said, because a key registered
         * nowhere looks perfectly good here and can only fail at the first fetch. Found by an
         * interface whose check would otherwise have passed one.
         */
        KEY_REFUSED,

        /** A local path. Nothing authenticates, and offering to store something would mislead. */
        NOT_NEEDED
    }

    /**
     * What a check found.
     *
     * @param outcome What it is.
     * @param credential The record that would be used, or {@code null}.
     * @param storeCommand What to run on this machine to fix it, or "".
     * @param detail One sentence for a person.
     */
    public record Check(Outcome outcome, @Nullable Credential credential, String storeCommand,
            String detail) {

        /**
         * Returns what to run on this machine, as arguments rather than a line to split.
         *
         * @return The command, empty when nothing would help.
         */
        public List<String> storeArguments() {
            return CredentialDeclarations.argumentsOf(storeCommand);
        }

        /**
         * Returns what that command needs on its standard input, in words.
         *
         * @return The description, or "" when it asks for the value itself.
         */
        public String storeStdin() {
            return storeCommand.contains("<") ? "the private key file" : "";
        }
    }

    /**
     * Splits a store command into arguments, dropping the shell redirection a person reads.
     * <p>
     * A key is a file and cannot be typed at a prompt, so what a person is shown ends in
     * {@code < <the private key file>}. A client runs the arguments and arranges the input itself,
     * which is why the two are answered separately rather than a client splitting a line it did
     * not write.
     *
     * @param command What a person would read.
     * @return The arguments.
     */
    public static List<String> argumentsOf(final String command) {
        if (command.isEmpty()) {
            return List.of();
        }
        final List<String> arguments = new ArrayList<>();
        for (final String word : command.split("\\s+")) {
            if (word.equals("<")) {
                break;
            }
            arguments.add(word);
        }
        return List.copyOf(arguments);
    }

    /**
     * Says which credential a destination would use and whether it would work.
     * <p>
     * Answers without touching the network, which is what makes it the call a dialog can make
     * before offering to do the thing.
     *
     * @param url Where something would connect.
     * @param purpose What for, such as {@code git}.
     * @return What it found.
     */
    public Check check(final String url, final String purpose) {
        final Credential declared = context.credentialRegistry().forUrl(purpose, url);
        if (declared == null) {
            if (GitCredentialNames.kindOf(url) == GitCredentialNames.Kind.NONE) {
                return new Check(Outcome.NOT_NEEDED, null, "",
                        "nothing authenticates to " + url + " - it is a path on this machine");
            }
            // Nothing declared, but the names a git URL implies may still hold something: a
            // machine that has never written a registry is the ordinary case, not a broken one.
            final Map<String, org.fuin.sokar.vault.VaultEntry> held =
                    context.readableCredentials().orElse(null);
            if (held == null) {
                return new Check(Outcome.VAULT_LOCKED, null, "",
                        "this account's vault is shut, so what it holds for " + url
                                + " cannot be read");
            }
            for (final String candidate : GitCredentialNames.candidatesFor(url)) {
                if (held.containsKey(candidate)) {
                    return new Check(Outcome.READY, new Credential(candidate,
                            GitCredentialNames.kindOf(url) == GitCredentialNames.Kind.KEY
                                    ? Credential.Kind.SSH_KEY : Credential.Kind.TOKEN,
                            CredentialRegistry.normalise(url), null, purpose,
                            Credential.Source.VAULT), "",
                            "'" + candidate + "' in the vault reaches " + url);
                }
            }
            return new Check(Outcome.NO_CREDENTIAL, null,
                    GitCredentialNames.storeCommandFor(url),
                    "nothing here reaches " + url + " - declare a credential for it, or store one"
                            + " under a name it implies");
        }
        if (declared.expiredAt(java.time.Instant.now())) {
            return new Check(Outcome.EXPIRED, declared, storeCommandFor(declared),
                    "'" + declared.id() + "' expired at " + declared.expires()
                            + " and nothing here can renew it without you");
        }
        return switch (declared.source()) {
            case AGENT -> new Check(Outcome.READY, declared, "",
                    "your own ssh-agent answers for " + url);
            case FILE -> Files.isReadable(Path.of(declared.id()))
                    ? new Check(Outcome.READY, declared, "",
                            declared.id() + " is what reaches " + url
                                    + " - this machine does not protect it")
                    : new Check(Outcome.MISSING_VALUE, declared, "",
                            declared.id() + " is declared for " + url + " and cannot be read");
            case ENVIRONMENT -> System.getenv(declared.id()) != null
                    ? new Check(Outcome.READY, declared, "",
                            "$" + declared.id() + " is what reaches " + url
                                    + " - this machine does not protect it")
                    : new Check(Outcome.MISSING_VALUE, declared, "",
                            "$" + declared.id() + " is declared for " + url + " and is not set");
            case VAULT -> {
                final Map<String, org.fuin.sokar.vault.VaultEntry> held =
                        context.readableCredentials().orElse(null);
                if (held == null) {
                    yield new Check(Outcome.VAULT_LOCKED, declared, "",
                            "'" + declared.id() + "' is declared for " + url
                                    + " and this account's vault is shut");
                }
                if (!context.vault().exists()) {
                    // No vault at all. A readable, empty answer covers this and "the vault is
                    // open and empty", and the two send a person to different places.
                    yield new Check(Outcome.NO_VAULT, declared, "sokar vault init",
                            "this account has no vault yet, so there is nowhere to put '"
                                    + declared.id() + "'");
                }
                yield held.containsKey(declared.id())
                        ? new Check(Outcome.READY, declared, "",
                                "'" + declared.id() + "' in the vault reaches " + url)
                        : new Check(Outcome.MISSING_VALUE, declared, storeCommandFor(declared),
                                "'" + declared.id() + "' is declared for " + url
                                        + " and the vault has no such entry");
            }
        };
    }

    /**
     * Returns what to type on this machine to give a record its value.
     *
     * @param credential The record.
     * @return The command, or "" when the value is not this machine's to store.
     */
    public static String storeCommandFor(final Credential credential) {
        return storeCommandFor(credential, null);
    }

    /**
     * Returns what to type on this machine to give a record its value.
     *
     * @param credential The record.
     * @param fromFile A file ON THAT MACHINE the value is in, or {@code null}. When it is given,
     *        nothing has to be piped or typed: the machine reads its own disk. That is what makes
     *        "use the key that is already here" one command a client runs unchanged, rather than a
     *        vault name a client had to compose - and the name only exists once somebody has said
     *        what the key is for, which is here.
     * @return The command, or "" when the value is not this machine's to store.
     */
    public static String storeCommandFor(final Credential credential,
            final @Nullable String fromFile) {
        if (credential.source() != Credential.Source.VAULT) {
            // It is already somewhere. Telling somebody to store it would be telling them to make
            // the copy they declared this record to avoid.
            return "";
        }
        if (fromFile != null && !fromFile.isBlank()) {
            return "sokar vault put " + credential.id() + " --from-file " + fromFile;
        }
        return "sokar vault put " + credential.id()
                + (credential.kind() == Credential.Kind.SSH_KEY ? " < <the private key file>" : "")
                + (credential.kind() == Credential.Kind.TOKEN ? " --type token" : "");
    }

    /**
     * Says what declaring this would do, and writes nothing.
     * <p>
     * Every refusal a real declaration gives - a kind that cannot open that destination, a public
     * key, a value that is not there, a shut vault - asked before the record exists. The last step
     * of a wizard, where somebody is about to rely on it.
     *
     * @param given What would be declared.
     * @return What it would be, and what is in the way.
     */
    public Check wouldDeclare(final Credential given) {
        final Credential credential;
        try {
            credential = named(given);
            checkPossible(credential);
        } catch (final org.fuin.sokar.core.credential.CredentialException ex) {
            return new Check(Outcome.NO_CREDENTIAL, null, "", String.valueOf(ex.getMessage()));
        }
        if (credential.source() == Credential.Source.VAULT && !context.vault().exists()) {
            // Asked here as well as in check(), because a destination nothing yet covers answers
            // NO_CREDENTIAL there - so the vault question never came up, and a machine with no
            // vault at all was told to run 'vault put'.
            return new Check(Outcome.NO_VAULT, credential, "sokar vault init",
                    "this account has no vault yet, so there is nowhere to put '"
                            + credential.id() + "'");
        }
        // The same question the check asks of a destination, asked of the record as it would be:
        // is the value actually there. One implementation, so a dry run cannot answer differently
        // from the thing it stands for.
        final Check about = check(credential.match(), credential.purpose());
        if (about.outcome() == Outcome.READY || about.credential() == null) {
            return new Check(present(credential) ? Outcome.READY : Outcome.MISSING_VALUE,
                    credential, storeCommandFor(credential),
                    present(credential) ? "it would be used for " + credential.match()
                            : "nothing holds its value yet");
        }
        return new Check(present(credential) ? Outcome.READY : about.outcome(), credential,
                storeCommandFor(credential), about.detail());
    }

    /**
     * Returns this credential with a name, when whoever declared it gave none.
     * <p>
     * <strong>One place, because it was two.</strong> A nameless declaration was fixed over the
     * socket and not at the terminal, so {@code credentials declare --vault ""} still wrote an
     * empty name and still offered {@code sokar vault put} with nothing after it - the same
     * defect, on the half nobody had looked at, on the very commit that fixed the other half.
     * Found by reading a published snapshot rather than by a test, which is the lesson.
     *
     * @param credential What was declared.
     * @return The same credential, named after the destination when it had no name.
     * @throws org.fuin.sokar.core.credential.CredentialException When no name can be built and
     *         none was given.
     */
    /**
     * Refuses a credential that cannot possibly open the destination it names.
     * <p>
     * <strong>The machine judges this, not a client.</strong> git over https never asks an ssh
     * agent anything, and an ssh destination never asks for a password - so an ssh key declared
     * for {@code https://} is a record that can only ever fail, at the moment somebody is trying
     * to get work done. It was accepted, and an interface had no business working out which
     * combinations are possible. Asked for by an interface that had two of them on a
     * machine.
     *
     * @param credential What was declared.
     * @throws org.fuin.sokar.core.credential.CredentialException When the kind cannot open that
     *         destination, saying which kind can.
     */
    public static void checkPossible(final Credential credential) {
        final GitCredentialNames.Kind wants = GitCredentialNames.kindOf(credential.match());
        if (wants == GitCredentialNames.Kind.NONE) {
            // A path on this machine. Nothing authenticates to it, so a credential for it is a
            // record that will never be read.
            throw new org.fuin.sokar.core.credential.CredentialException("nothing authenticates to"
                    + " '" + credential.match() + "' - it is a path on this machine, not an"
                    + " address something connects to");
        }
        if (credential.source() == Credential.Source.FILE && credential.id().endsWith(".pub")) {
            // The public half is not a credential, and a machine can tell. Declared cleanly with
            // 'present: true', it would have failed at the first fetch instead - which is the
            // mistake anybody makes once and nobody enjoys finding.
            throw new org.fuin.sokar.core.credential.CredentialException(credential.id()
                    + " is a public key. A machine signs with the private half - the same path"
                    + " without '.pub'.");
        }
        final boolean key = credential.kind() == Credential.Kind.SSH_KEY;
        if (wants == GitCredentialNames.Kind.KEY && !key) {
            throw new org.fuin.sokar.core.credential.CredentialException("'" + credential.match()
                    + "' is reached over ssh, which asks for a key and never for a token or a"
                    + " password. Declare it as ssh-key, or name an https address.");
        }
        if (wants == GitCredentialNames.Kind.TOKEN && key) {
            throw new org.fuin.sokar.core.credential.CredentialException("'" + credential.match()
                    + "' is reached over https, which asks for a token or a username and password"
                    + " and never for an ssh key. Declare it as token, basic or oauth, or name an"
                    + " ssh address.");
        }
    }

    public static Credential named(final Credential credential) {
        if (!credential.id().isBlank()
                || credential.source() == Credential.Source.AGENT) {
            return credential;
        }
        final String implied = GitCredentialNames.impliedName(
                credential.kind() == Credential.Kind.SSH_KEY
                        ? GitCredentialNames.Kind.KEY : GitCredentialNames.Kind.TOKEN,
                credential.match());
        if (implied == null) {
            throw new org.fuin.sokar.core.credential.CredentialException("'" + credential.match()
                    + "' names no host to build a name from, so give the credential a name");
        }
        return new Credential(implied, credential.kind(), credential.match(), credential.user(),
                credential.purpose(), credential.source(), credential.expires());
    }

    /**
     * Writes a record down, replacing one for the same destination.
     *
     * @param credential What to record.
     * @return What was written.
     * @throws IOException If the file cannot be written.
     */
    public Credential declare(final Credential given) throws IOException {
        // Named here, so every way in gets it: a nameless record is the one thing that cannot be
        // stored into, removed, or reported about.
        final Credential credential = named(given);
        checkPossible(credential);
        final List<Credential> kept = new ArrayList<>();
        for (final Credential existing : context.credentialRegistry().all()) {
            if (!existing.match().equals(credential.match())
                    || !existing.purpose().equals(credential.purpose())) {
                kept.add(existing);
            }
        }
        kept.add(credential);
        write(kept);
        return credential;
    }

    /**
     * Forgets the record for a destination.
     *
     * @param match The destination, as a record gives it.
     * @return What still holds a value, or {@code null} when nothing was forgotten.
     * @throws IOException If the file cannot be written.
     */
    public @Nullable String forget(final String match) throws IOException {
        final String wanted = CredentialRegistry.normalise(match);
        final List<Credential> kept = new ArrayList<>();
        Credential gone = null;
        for (final Credential existing : context.credentialRegistry().all()) {
            if (existing.match().equals(wanted)) {
                gone = existing;
            } else {
                kept.add(existing);
            }
        }
        if (gone == null) {
            return null;
        }
        write(kept);
        // The secret is NOT removed: a key in somebody's ~/.ssh is theirs, and a vault entry is
        // removed by a person at the machine. Naming what is left lets an interface offer that
        // second step instead of implying the secret is gone.
        return switch (gone.source()) {
            case VAULT -> "'" + gone.id() + "' is still in the vault"
                    + " - 'sokar vault remove " + gone.id() + "' removes it";
            case FILE -> gone.id() + " is untouched";
            case ENVIRONMENT -> "$" + gone.id() + " is untouched";
            case AGENT -> "";
        };
    }

    private void write(final List<Credential> credentials) throws IOException {
        final Path file = context.paths().vault().credentialRegistry();
        Files.createDirectories(file.getParent());
        final StringBuilder text = new StringBuilder("""
                # What this machine connects out with. No secret is in here: this says what kind
                # of credential each destination wants, and where its value lives. Written by
                # 'sokar' and by an interface; safe to read with the vault shut, which is the
                # point of keeping it outside.
                credentials:
                """);
        for (final Credential credential : credentials) {
            text.append("  - match: \"").append(credential.match()).append("\"\n");
            text.append("    kind: ").append(credential.kind().name()
                    .toLowerCase(Locale.ROOT).replace('_', '-')).append('\n');
            text.append("    source: ").append(credential.source().name()
                    .toLowerCase(Locale.ROOT)).append('\n');
            switch (credential.source()) {
                case VAULT -> text.append("    vault: \"").append(credential.id()).append("\"\n");
                case FILE -> text.append("    file: \"").append(credential.id()).append("\"\n");
                case ENVIRONMENT ->
                        text.append("    env: \"").append(credential.id()).append("\"\n");
                case AGENT -> text.append("    # the account's own ssh-agent answers\n");
            }
            if (credential.user() != null && !credential.user().isBlank()) {
                text.append("    user: \"").append(credential.user()).append("\"\n");
            }
            if (!Credential.ANY.equals(credential.purpose())) {
                text.append("    purpose: ").append(credential.purpose()).append('\n');
            }
            if (credential.expires() != null) {
                text.append("    expires: \"").append(credential.expires()).append("\"\n");
            }
        }
        Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Returns every record with whether its value is actually there, for a listing.
     *
     * @return One map per record, ready for a wire.
     */
    public List<Map<String, Object>> asMaps() {
        final List<Map<String, Object>> rows = new ArrayList<>();
        for (final Credential credential : context.credentialRegistry().all()) {
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", credential.id());
            row.put("kind", credential.kind().name());
            row.put("match", credential.match());
            row.put("user", credential.user() == null ? "" : credential.user());
            row.put("purpose", credential.purpose());
            row.put("source", credential.source().name());
            row.put("protected", credential.protectedHere());
            row.put("present", present(credential));
            row.put("expires", credential.expires() == null ? "" : credential.expires());
            rows.add(row);
        }
        return rows;
    }

    /**
     * Tells whether the value of a record is actually there right now.
     * <p>
     * Asked of a record that may not be in the file yet, which is what a dry run is about.
     *
     * @param credential The record.
     * @return {@code true} when something holds its value.
     */
    public boolean holdsValue(final Credential credential) {
        return present(credential);
    }

    private boolean present(final Credential credential) {
        return switch (credential.source()) {
            case AGENT -> true;
            case FILE -> Files.isReadable(Path.of(credential.id()));
            case ENVIRONMENT -> System.getenv(credential.id()) != null;
            case VAULT -> context.readableCredentials()
                    .map(held -> held.containsKey(credential.id())).orElse(false);
        };
    }
}
