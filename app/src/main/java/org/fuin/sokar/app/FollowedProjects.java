package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.wire.Json;

/**
 * The project repositories this account follows, and what happened last time it tried.
 * <p>
 * <strong>A person puts one here and nothing else does.</strong> Nothing discovers projects, and no
 * commit adds one: a configuration source that can enrol further configuration sources is a source
 * that grows where nobody is looking. So this list is short, deliberate, and only ever changed by
 * somebody saying so.
 */
public final class FollowedProjects {

    /** What a project's name may be: the same rule the project file itself enforces. */
    private static final java.util.regex.Pattern NAME =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

    /**
     * One followed project, as this account last knew it.
     *
     * @param name The project's name, which is also its directory and its container prefix.
     * @param url Where its repository is.
     * @param commit The last commit this machine verified and applied, or "" when none ever was.
     * @param at When it last tried, RFC 3339, or "" when it never has.
     * @param outcome What that attempt was, or "" when it never has.
     * @param detail What to tell an operator about that attempt, or "".
     * @param refused The commit that attempt turned away, or "" when it turned nothing away. Not
     *        the same as {@code commit}, which is what is in force.
     * @param signer The fingerprint of the key that signed the refused commit, or "".
     * @param unverified Whether this project is followed <strong>without an anchor</strong>: what
     *        it says is applied without any signature being checked.
     *        <p>
     *        A state rather than an error. Access to a git repository is already authenticated and
     *        people do apply what an authenticated clone gives them - but without a signature the
     *        rule is <em>whoever may push here decides what tasks on this machine may reach</em>,
     *        rather than <em>whoever holds the signing key</em>, and that belongs on screen
     *        wherever this project is.
     */
    public record Followed(String name, String url, String commit, String at, String outcome,
            String detail, String refused, String signer, boolean unverified) {

        /**
         * Constructor for a record that turned nothing away.
         *
         * @param name Project name.
         * @param url Where its repository is.
         * @param commit The commit in force, or "".
         * @param at When it was last tried, or "".
         * @param outcome What happened, or "".
         * @param detail What to tell an operator, or "".
         */
        public Followed(String name, String url, String commit, String at, String outcome,
                String detail) {
            this(name, url, commit, at, outcome, detail, "", "", false);
        }

        /**
         * Tells whether nothing will change about this project until somebody acts.
         * <p>
         * <strong>Derived from the outcome, never stored.</strong> Two fields that must agree are
         * two fields that can disagree, and this one would rot the first time an outcome was
         * added. Which outcomes need a person is knowledge this side has - an unreachable
         * repository may answer on the next pass by itself, a refused signature never will - and
         * an interface asking it should not have to keep a list of them in step with ours.
         *
         * @return {@code true} when it is stuck until a person does something.
         */
        public boolean needsAPerson() {
            return switch (outcome) {
                case "NOT_SIGNED", "UNKNOWN_KEY", "NO_ANCHOR", "REWRITTEN", "UNUSABLE",
                        "VAULT_LOCKED" -> true;
                default -> false;
            };
        }

        /**
         * Constructor for a record from before unverified following existed.
         *
         * @param name Project name.
         * @param url Where its repository is.
         * @param commit The commit in force, or "".
         * @param at When it was last tried, or "".
         * @param outcome What happened, or "".
         * @param detail What to tell an operator, or "".
         * @param refused The commit turned away, or "".
         * @param signer The fingerprint that signed it, or "".
         */
        public Followed(String name, String url, String commit, String at, String outcome,
                String detail, String refused, String signer) {
            this(name, url, commit, at, outcome, detail, refused, signer, false);
        }

        /**
         * Returns this record with nothing in force any more.
         * <p>
         * What accepting a rewrite is: the next reconcile has nothing to descend from and applies
         * what it verifies. Everything else about the follow is kept - <strong>including whether
         * it was taken without an anchor</strong>, which is the field this used to lose. Both
         * callers rebuilt the record by hand through a constructor that defaults the flag, so
         * accepting a rewrite quietly turned an unverified follow into one that claims a
         * signature was checked. A method, so the next field that is added cannot be forgotten
         * in two places at once.
         *
         * @return A copy with no commit in force.
         */
        public Followed forgettingWhatIsInForce() {
            return new Followed(name, url, "", at, outcome, detail, refused, signer, unverified);
        }
    }

    private final Path directory;

    /**
     * Constructor.
     *
     * @param directory Where the records are.
     */
    public FollowedProjects(final Path directory) {
        this.directory = directory;
    }

    /**
     * Starts following a project.
     *
     * @param name The project's name.
     * @param url Its repository.
     * @return What is now recorded.
     * @throws IOException Writing failed.
     * @throws IllegalArgumentException If the name is not one a project may have.
     */
    public Followed follow(final String name, final String url) throws IOException {
        return follow(name, url, false);
    }

    /**
     * Starts following a project, with or without an anchor.
     *
     * @param name The project's name.
     * @param url Its repository.
     * @param unverified Whether to apply what it says without checking a signature.
     * @return What is now recorded.
     * @throws IOException Writing failed.
     * @throws IllegalArgumentException If the name is not one a project may have.
     */
    public Followed follow(final String name, final String url, final boolean unverified)
            throws IOException {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A project name is lower-case letters, digits and"
                    + " hyphens, starting with a letter or digit: " + name);
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("A project needs the address of its repository");
        }
        final Followed existing = find(name);
        // Following the same project twice is not an error and not a second entry: it is somebody
        // making sure. Following it at a different address is a different project wearing a name,
        // and that is refused rather than silently repointed.
        if (existing != null && !existing.url().equals(url)) {
            throw new IllegalArgumentException("'" + name + "' already follows " + existing.url()
                    + ". Stop following it first if you mean to point it somewhere else.");
        }
        final Followed followed = existing != null
                ? new Followed(existing.name(), existing.url(), existing.commit(), existing.at(),
                        existing.outcome(), existing.detail(), existing.refused(),
                        existing.signer(), unverified)
                : new Followed(name, url, "", "", "", "", "", "", unverified);
        write(followed);
        return followed;
    }

    /**
     * Removes a clone left by a follow that is being taken back.
     * <p>
     * A first follow that cannot apply leaves nothing behind, and the clone is the other half of
     * "nothing": the record says whether the project is followed, and the directory is what a
     * later reader would find. Failing to remove it is not worth failing over - the record is
     * what decides - so this reports nothing.
     *
     * @param root The followed clone.
     */
    public static void forget(final Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.walk(root)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ex) {
                    return;
                }
            });
        } catch (final IOException ex) {
            return;
        }
    }

    /**
     * Records what an attempt to follow did.
     *
     * @param followed What is now known.
     * @throws IOException Writing failed.
     */
    public void write(final Followed followed) throws IOException {
        final Map<String, Object> document = new LinkedHashMap<>();
        document.put("name", followed.name());
        document.put("url", followed.url());
        document.put("commit", followed.commit());
        document.put("at", followed.at());
        document.put("outcome", followed.outcome());
        document.put("detail", followed.detail());
        // Kept, because a refusal outlives the pass that found it: the project goes on running
        // what it had, and a person coming back tomorrow still has to see what was turned away.
        document.put("refused", followed.refused());
        document.put("signer", followed.signer());
        document.put("unverified", followed.unverified());
        Files.createDirectories(directory);
        final Path file = directory.resolve(followed.name() + ".json");
        final Path staged = directory.resolve("." + followed.name() + ".json.tmp");
        Files.writeString(staged, Json.write(document), StandardCharsets.UTF_8);
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Stops following a project, forgetting only this record.
     * <p>
     * What else belongs to that project - its clone, its mirror, its tasks - is not this class's to
     * delete, and deleting it is guarded elsewhere: a mirror may hold work nobody has reviewed.
     *
     * @param name The project.
     * @return {@code true} when there was one.
     * @throws IOException Deleting failed.
     */
    public boolean unfollow(final String name) throws IOException {
        return Files.deleteIfExists(directory.resolve(name + ".json"));
    }

    /**
     * Returns a project the caller knows is followed, such as one it has just followed.
     *
     * @param name The project.
     * @return What is recorded.
     * @throws IOException Reading failed.
     * @throws IllegalStateException If nothing is recorded, which would be a fault here.
     */
    public Followed require(final String name) throws IOException {
        final Followed followed = find(name);
        if (followed == null) {
            throw new IllegalStateException("project " + name + " is followed and has no record");
        }
        return followed;
    }

    /**
     * Returns one followed project.
     *
     * @param name The project.
     * @return What is recorded, or {@code null}.
     * @throws IOException Reading failed.
     */
    public @org.jspecify.annotations.Nullable Followed find(final String name) throws IOException {
        for (final Followed followed : all()) {
            if (followed.name().equals(name)) {
                return followed;
            }
        }
        return null;
    }

    /**
     * Returns every project this account follows.
     *
     * @return The records, by name. Empty when this account follows nothing.
     * @throws IOException Reading failed.
     */
    public List<Followed> all() throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        final List<Followed> found = new ArrayList<>();
        try (Stream<Path> entries = Files.list(directory)) {
            for (final Path file : entries.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                final Followed followed = read(file);
                if (followed != null) {
                    found.add(followed);
                }
            }
        }
        return List.copyOf(found);
    }

    private @org.jspecify.annotations.Nullable Followed read(final Path file) throws IOException {
        final Object parsed;
        try {
            parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (final RuntimeException ex) {
            // Not a record this wrote. Ignored rather than thrown: one unreadable file must not
            // stop an account following everything else - which is what happened before a test
            // put a file that was not JSON beside a good one.
            return null;
        }
        if (!(parsed instanceof Map<?, ?> document)) {
            return null;
        }
        final String name = text(document, "name");
        final String url = text(document, "url");
        if (name.isBlank() || url.isBlank()) {
            return null;
        }
        return new Followed(name, url, text(document, "commit"), text(document, "at"),
                text(document, "outcome"), text(document, "detail"),
                text(document, "refused"), text(document, "signer"),
                Boolean.TRUE.equals(document.get("unverified")));
    }

    private static String text(final Map<?, ?> document, final String key) {
        return document.get(key) instanceof String value ? value : "";
    }
}
