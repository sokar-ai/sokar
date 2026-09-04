package org.fuin.sokar.gate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/**
 * A bare mirror an agent pushes into, and from which nothing leaves unreviewed.
 * <p>
 * The agent never has the upstream's credentials and never reaches the upstream: it pushes to this
 * mirror over a local HTTP endpoint. Forwarding is a separate, explicit act by the operator.
 * <p>
 * <strong>Agent pushes land under {@code refs/sokar/incoming/}, never on a branch.</strong> A push
 * to {@code refs/heads/main} would make the mirror's own branches move under the operator while
 * they are reading them, and would let an agent rewrite history that had already been reviewed.
 * Incoming refs are inert: nothing reads them but the review command.
 */
public class GitGate {

    /** Namespace every agent push lands in. */
    public static final String INCOMING = "refs/sokar/incoming/";

    private final CommandRunner runner;

    private final Path mirror;

    private final GateMode mode;

    @Nullable
    private final String upstreamUrl;

    @Nullable
    private final String seedUrl;

    /**
     * Constructor for a gate whose mirror is seeded from the upstream it forwards to.
     *
     * @param runner Runs git.
     * @param mirror Directory of the bare mirror.
     * @param mode What the gate may do.
     * @param upstreamUrl Upstream to forward to, or {@code null} if there is none.
     */
    public GitGate(CommandRunner runner, Path mirror, GateMode mode, @Nullable String upstreamUrl) {
        this(runner, mirror, mode, upstreamUrl, upstreamUrl);
    }

    /**
     * Constructor.
     * <p>
     * The two URLs are separate because a mirror may be seeded from a checkout on this machine,
     * which is never where an approved push should be forwarded.
     *
     * @param runner Runs git.
     * @param mirror Directory of the bare mirror.
     * @param mode What the gate may do.
     * @param upstreamUrl Upstream to forward to, or {@code null} if there is none.
     * @param seedUrl Repository the mirror is first cloned from, or {@code null} to start empty.
     */
    public GitGate(CommandRunner runner, Path mirror, GateMode mode, @Nullable String upstreamUrl,
            @Nullable String seedUrl) {
        this.runner = runner;
        this.mirror = mirror;
        this.mode = mode;
        this.upstreamUrl = upstreamUrl;
        this.seedUrl = seedUrl;
    }

    /**
     * Returns the mirror directory.
     *
     * @return Mirror path.
     */
    public Path mirror() {
        return mirror;
    }

    /**
     * Returns the mode.
     *
     * @return Gate mode.
     */
    public GateMode mode() {
        return mode;
    }

    /**
     * Creates the bare mirror if it is not there, cloning the seed when one is configured.
     *
     * @throws GateException If the mirror cannot be created.
     */
    public void initialise() {
        if (Files.isDirectory(mirror.resolve("objects"))) {
            return;
        }
        try {
            Files.createDirectories(mirror);
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot create " + mirror, ex);
        }
        if (seedUrl == null) {
            git("init", "--bare", "--initial-branch=main", mirror.toString());
        } else {
            git("clone", "--bare", seedUrl, mirror.toString());
        }
        // The agent pushes over HTTP, and git refuses that on a bare repository unless told the
        // repository is meant to be served.
        gitIn("config", "http.receivepack", "true");
        gitIn("config", "receive.denyCurrentBranch", "ignore");
    }

    /**
     * Returns where the mirror was cloned from.
     * <p>
     * Recorded by {@code git clone --bare} itself, so it is the mirror that answers rather than
     * any configuration that might since have changed.
     *
     * @return The seed URL, or {@code null} for a mirror that was created empty.
     */
    @Nullable
    public String seededFrom() {
        final CommandResult result = runner.run(Command.of(List.of("git", "--git-dir",
                mirror.toString(), "config", "--get", "remote.origin.url")));
        if (!result.successful()) {
            return null;
        }
        final String url = result.standardOutput().strip();
        return url.isEmpty() ? null : url;
    }

    /**
     * Returns the refs an agent has pushed and nobody has reviewed.
     *
     * @return Incoming refs, without the namespace prefix.
     */
    public List<String> pending() {
        final CommandResult result = gitIn("for-each-ref", "--format=%(refname)", INCOMING);
        final List<String> refs = new ArrayList<>();
        result.standardOutput().lines()
                .map(String::strip)
                .filter(line -> line.startsWith(INCOMING))
                .forEach(line -> refs.add(line.substring(INCOMING.length())));
        return List.copyOf(refs);
    }

    /**
     * Returns the pending pushes with the information needed to judge them, oldest first.
     * <p>
     * Ordered by age rather than by name, because the oldest is the one most likely to have been
     * forgotten, and a forgotten queue is how a gate turns into a rubber stamp.
     *
     * @return Pending pushes.
     */
    public List<PendingPush> pendingDetail() {
        final CommandResult result = gitIn("for-each-ref",
                "--format=%(refname)\t%(objectname:short)\t%(committerdate:unix)\t%(contents:subject)",
                "--sort=committerdate", INCOMING);
        final List<PendingPush> pushes = new ArrayList<>();
        result.standardOutput().lines().forEach(line -> {
            final String[] fields = line.split("\t", 4);
            if (fields.length == 4 && fields[0].startsWith(INCOMING)) {
                pushes.add(new PendingPush(fields[0].substring(INCOMING.length()), fields[1],
                        fields[3], java.time.Instant.ofEpochSecond(Long.parseLong(fields[2].strip()))));
            }
        });
        return List.copyOf(pushes);
    }

    /**
     * Writes a bundle holding everything in the mirror, including the pending pushes.
     * <p>
     * A git bundle rather than a copy of the directory: it is a single file, it is verifiable with
     * {@code git bundle verify}, and it can be cloned from directly. A tarball of a live
     * repository can catch it mid-write.
     *
     * @param bundle Where to write it.
     * @throws GateException If the bundle cannot be written.
     */
    public void backup(Path bundle) {
        try {
            Files.createDirectories(bundle.toAbsolutePath().getParent());
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot create " + bundle.getParent(), ex);
        }
        if (gitIn("for-each-ref").standardOutput().isBlank()) {
            // git refuses to bundle an empty repository, and an operator who asked for a backup
            // should be told that rather than shown a git error about a bad revision.
            throw new GateException("The mirror has no refs yet, so there is nothing to back up");
        }
        gitIn("bundle", "create", bundle.toString(), "--all");
    }

    /**
     * Tells whether a bundle is intact.
     *
     * @param bundle The bundle.
     * @return {@code true} if git can verify it.
     */
    public boolean verifyBackup(Path bundle) {
        // git refuses to verify a bundle without a repository to verify it against - it has to
        // check whether the bundle's prerequisites are present. So this needs a git directory,
        // and restore() creates an empty one before calling it.
        if (!Files.isDirectory(mirror.resolve("objects"))) {
            throw new GateException("Cannot verify " + bundle
                    + " without a repository: git needs one to check the bundle's prerequisites");
        }
        return runner.run(Command.of(List.of("git", "--git-dir", mirror.toString(),
                "bundle", "verify", bundle.toString()))).successful();
    }

    /**
     * Restores a mirror from a bundle.
     * <p>
     * Refuses to write over an existing mirror. Restoring in place would silently discard whatever
     * the agent has pushed since the backup, which is precisely the work an operator restoring a
     * backup is least able to reconstruct.
     *
     * @param bundle The bundle to restore from.
     * @throws GateException If the mirror already exists or the bundle is unusable.
     */
    public void restore(Path bundle) {
        if (!Files.isRegularFile(bundle)) {
            throw new GateException("No bundle at " + bundle);
        }
        if (Files.isDirectory(mirror.resolve("objects"))) {
            throw new GateException("A mirror already exists at " + mirror
                    + ". Move it aside first: restoring over it would discard whatever has been"
                    + " pushed since the backup");
        }
        // The empty repository has to exist before the bundle can be verified: git checks the
        // bundle's prerequisites against an object store, and refuses outright without one.
        git("init", "--bare", mirror.toString());
        if (!verifyBackup(bundle)) {
            deleteMirror();
            throw new GateException(bundle + " is not a usable git bundle");
        }
        // Not 'clone --bare': that takes refs/heads/* and silently drops everything else,
        // including every pending push - which is exactly the work an operator restoring a backup
        // is least able to reconstruct. Fetching refs/*:refs/* takes all of it.
        gitIn("fetch", bundle.toString(), "refs/*:refs/*");
        gitIn("config", "http.receivepack", "true");
        gitIn("config", "receive.denyCurrentBranch", "ignore");
    }

    /**
     * Tells whether a ref exists in the mirror.
     *
     * @param ref Ref name.
     * @return {@code true} if git can resolve it.
     */
    public boolean resolves(String ref) {
        return runner.run(Command.of(List.of("git", "--git-dir", mirror.toString(),
                "rev-parse", "--verify", "--quiet", ref))).successful();
    }

    /**
     * Returns what an incoming ref would change, as a patch.
     * <p>
     * A mirror that was created empty has no branch to compare against, so the first review of
     * every new project would otherwise fail with a git error about an ambiguous argument. When
     * the base is missing the whole ref is shown instead, which is the right answer: all of it is
     * new.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} for the whole ref.
     * @return Unified diff.
     */
    public String review(String name, @Nullable String against) {
        requirePending(name);
        if (against == null || against.isBlank() || !resolves(against)) {
            return gitIn("show", "--patch", INCOMING + name).standardOutput();
        }
        return gitIn("diff", against, INCOMING + name).standardOutput();
    }

    /**
     * Returns the commits an incoming ref adds.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} to list the whole history of the ref.
     * @return One line per commit.
     */
    public String log(String name, @Nullable String against) {
        requirePending(name);
        final String range = against == null || against.isBlank() || !resolves(against)
                ? INCOMING + name
                : against + ".." + INCOMING + name;
        return gitIn("log", "--oneline", range).standardOutput();
    }

    /**
     * Forwards an incoming ref to the upstream.
     * <p>
     * The only method that sends anything off the machine, and it is never called by anything the
     * agent can reach.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to push to.
     * @throws GateException If the mode forbids forwarding, or no upstream is configured.
     */
    public void approve(String name, String branch) {
        if (!mode.canForward()) {
            throw new GateException("This project's security class is offline, so nothing is"
                    + " forwarded upstream");
        }
        if (upstreamUrl == null) {
            throw new GateException("No upstream is configured for this project");
        }
        requirePending(name);
        gitIn("push", upstreamUrl, INCOMING + name + ":refs/heads/" + branch);
        gitIn("update-ref", "-d", INCOMING + name);
    }

    /**
     * Discards an incoming ref without forwarding it.
     *
     * @param name Incoming ref name, without the namespace prefix.
     */
    public void reject(String name) {
        gitIn("update-ref", "-d", INCOMING + name);
    }

    private void deleteMirror() {
        // The half-made repository would otherwise sit there and make the next restore refuse,
        // telling the operator a mirror exists when what exists is the wreckage of this attempt.
        try (var paths = Files.walk(mirror)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (java.io.IOException ex) {
                    // Best effort on a cleanup path.
                }
            });
        } catch (java.io.IOException ex) {
            // Best effort on a cleanup path.
        }
    }

    private void requirePending(String name) {
        // Otherwise every mistyped name produces a git error about an ambiguous argument, which
        // says nothing about what the operator actually got wrong.
        if (!pending().contains(name)) {
            throw new GateException("There is no pending push named '" + name + "'"
                    + (pending().isEmpty() ? ", nothing is pending" : ", try: " + String.join(", ", pending())));
        }
    }

    private CommandResult git(String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        try {
            return runner.runOrFail(Command.of(all));
        } catch (CommandException ex) {
            throw new GateException(ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
        }
    }

    private CommandResult gitIn(String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git", "--git-dir", mirror.toString()));
        all.addAll(List.of(arguments));
        try {
            return runner.runOrFail(Command.of(all));
        } catch (CommandException ex) {
            throw new GateException(ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
        }
    }
}
