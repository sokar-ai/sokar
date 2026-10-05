package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import org.jspecify.annotations.Nullable;

/**
 * The keys a followed project's configuration is taken from, handed on by the keys before them.
 * <p>
 * A person names the first key at the follow, out of band. After that the project's own file says whose
 * commits change it, under {@code project.signers}: a commit that changes that list counts only when it is
 * signed by a key in force before it, so the keys pinned here hand on to the ones it names - and a key never
 * vouches for the commit that adds it. Every commit since the one in force that changes the list is checked
 * in order, so a key added and removed again in between is followed through. A project whose file names no
 * signers keeps the keys pinned at the follow.
 */
final class SignerHandover {

    private static final String FILE = "project.yml";

    /**
     * What the commits since the one in force hand on.
     *
     * @param keys The keys in force after the last of them, each as type and key.
     * @param changed Whether they differ from the ones pinned here.
     * @param tip The verdict on the commit fetched when it changed the list itself, checked with the keys before
     *        it; {@code null} when it did not, and it is checked with {@link #keys()}.
     * @param refused The verdict on a commit that changed the list without being signed by a key in force, or
     *        {@code null}.
     */
    record Handed(List<String> keys, boolean changed, ConfigurationGate.@Nullable Verdict tip,
            ConfigurationGate.@Nullable Verdict refused) {
    }

    private final CommandRunner runner;
    private final Path signers;

    /**
     * Constructor.
     *
     * @param runner How git is run.
     * @param signers The pinned keys, in OpenSSH's allowed_signers format, by project.
     */
    SignerHandover(final CommandRunner runner, final Path signers) {
        this.runner = runner;
        this.signers = signers;
    }

    /**
     * Follows the keys from the commit in force to the one fetched.
     *
     * @param clone The followed clone.
     * @param project The project, the principal its keys are pinned under.
     * @param inForce The commit in force, or "" before the first.
     * @param tip The commit fetched.
     * @return What they hand on.
     */
    Handed walk(final Path clone, final String project, final String inForce, final String tip) {
        final List<String> pinned = pinned(project);
        if (tip.isEmpty()) {
            return new Handed(pinned, false, null, null);
        }
        List<String> keys = pinned;
        final List<String> commits;
        Set<String> last;
        if (inForce.isEmpty()) {
            // The first commit taken: only it, against the key the person named. Older history may be signed by
            // keys long retired, and none of it is what is applied.
            commits = List.of(tip);
            last = new LinkedHashSet<>(pinned);
        } else {
            commits = between(clone, inForce, tip);
            List<String> before;
            try {
                before = at(clone, inForce);
            } catch (final ProjectException ex) {
                before = null;
            }
            last = before == null ? null : new LinkedHashSet<>(before);
        }
        ConfigurationGate.Verdict tipVerdict = null;
        for (final String commit : commits) {
            final List<String> named;
            try {
                named = at(clone, commit);
            } catch (final ProjectException ex) {
                // A list that cannot be read is no list: what the commit means is left to the reader, which
                // refuses the file when it is the one applied.
                continue;
            }
            if (named == null || new LinkedHashSet<>(named).equals(last)) {
                continue;
            }
            final ConfigurationGate.Verdict verdict = verify(clone, project, keys, commit);
            if (!verdict.mayApply()) {
                return new Handed(keys, false, null, new ConfigurationGate.Verdict(verdict.outcome(),
                        verdict.commit(), verdict.signer(), "changes project.signers and is not signed by a key"
                                + " in force before it, so it is not applied: " + verdict.detail()));
            }
            keys = named;
            last = new LinkedHashSet<>(named);
            if (commit.equals(tip)) {
                tipVerdict = verdict;
            }
        }
        return new Handed(keys, !new LinkedHashSet<>(keys).equals(new LinkedHashSet<>(pinned)), tipVerdict, null);
    }

    /**
     * Checks a commit against the given keys.
     *
     * @param clone The followed clone.
     * @param project The principal.
     * @param keys The keys.
     * @param ref What to check.
     * @return The verdict.
     */
    ConfigurationGate.Verdict verify(final Path clone, final String project, final List<String> keys,
            final String ref) {
        Path own = null;
        try {
            own = Files.createTempFile("sokar-signers-", "");
            Files.write(own, keys.stream().map(key -> project + " " + key).toList());
            return new ConfigurationGate(runner, own).verify(clone, ref);
        } catch (final IOException ex) {
            return new ConfigurationGate.Verdict(ConfigurationGate.Outcome.UNREADABLE, "", "",
                    "the keys in force could not be written down: " + ex.getMessage());
        } finally {
            if (own != null) {
                try {
                    Files.deleteIfExists(own);
                } catch (final IOException ignored) {
                    // A temporary file of public keys; nothing secret is left.
                }
            }
        }
    }

    /**
     * Pins the keys handed on for one project, in place of the ones it had, and says what changed.
     *
     * @param project The project.
     * @param keys What is in force now.
     * @return What was pinned and unpinned, by fingerprint, for a person to read; "" when nothing changed.
     * @throws IOException If the file cannot be written.
     */
    String pin(final String project, final List<String> keys) throws IOException {
        final List<String> before = pinned(project);
        final List<String> lines = new ArrayList<>();
        if (Files.isRegularFile(signers)) {
            for (final String line : Files.readAllLines(signers)) {
                if (!line.startsWith(project + " ")) {
                    lines.add(line);
                }
            }
        }
        keys.forEach(key -> lines.add(project + " " + key));
        Files.createDirectories(signers.toAbsolutePath().getParent());
        final Path staged = Files.createTempFile(signers.toAbsolutePath().getParent(), ".project-signers-", "");
        Files.write(staged, lines);
        Files.move(staged, signers, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        final List<String> said = new ArrayList<>();
        keys.stream().filter(key -> !before.contains(key))
                .forEach(key -> said.add("pinned " + fingerprint(key)));
        before.stream().filter(key -> !keys.contains(key))
                .forEach(key -> said.add("unpinned " + fingerprint(key)));
        return said.isEmpty() ? "" : String.join(", ", said) + " for " + project + ", as project.signers says";
    }

    private static String fingerprint(final String key) {
        final String found = SignedBy.fingerprintOf(key);
        return found == null ? key : found;
    }

    private List<String> pinned(final String project) {
        final List<String> keys = new ArrayList<>();
        try {
            if (Files.isRegularFile(signers)) {
                for (final String line : Files.readAllLines(signers)) {
                    final String[] fields = line.strip().split("\\s+");
                    if (fields.length >= 3 && fields[0].equals(project)) {
                        keys.add(fields[1] + " " + fields[2]);
                    }
                }
            }
        } catch (final IOException ex) {
            // Unreadable is nothing pinned; the gate says so on its own.
        }
        return keys;
    }

    private List<String> between(final Path clone, final String older, final String newer) {
        // First parent: the line of commits the project's main moved along, each one as it was taken -
        // a merge counts as the commit that brought the change.
        final CommandResult listed = runner.run(Command.of("git", "-C", clone.toString(), "rev-list",
                "--reverse", "--first-parent", older + ".." + newer));
        return listed.successful() ? listed.standardOutput().lines().map(String::strip)
                .filter(line -> !line.isEmpty()).toList() : List.of();
    }

    private @Nullable List<String> at(final Path clone, final String commit) {
        final CommandResult shown = runner.run(Command.of("git", "-C", clone.toString(), "show",
                commit + ":" + FILE));
        return shown.successful() ? ProjectReader.signers(shown.standardOutput(), FILE + " at " + commit) : null;
    }
}
