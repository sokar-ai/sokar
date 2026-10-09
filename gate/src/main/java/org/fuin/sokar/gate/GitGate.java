package org.fuin.sokar.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /**
     * After a task's own ref: where what it never handed back is rescued to when the task is removed - beside
     * its reviewed work, never in it.
     */
    public static final String RESCUED = "-rescued";

    private final CommandRunner runner;

    private final Path mirror;

    private final GateMode mode;

    @Nullable
    private final String upstreamUrl;

    @Nullable
    private final String seedUrl;

    /** Where a credential for a remote comes from. Nothing, until a caller that has one says so. */
    private GitCredentials lending = GitCredentials.NONE;

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
     * Returns this gate with a credential to reach the upstream with.
     * <p>
     * Only the commands that talk to a real remote use it. Everything else here is local to this
     * machine and has nothing to authenticate to.
     *
     * @param lending Where a credential for a URL comes from.
     * @return This gate.
     */
    public GitGate using(final GitCredentials lending) {
        this.lending = lending;
        return this;
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
    public void initialize() {
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
            // Through the lending, as every later call to the upstream is: an ssh seed needs the machine's host-key
            // policy and its own known hosts. A bare 'git clone' had neither, so a host this machine had vouched for
            // failed as "Host key verification failed" and the task started with no gate.
            try (GitCredentials.Lease lease = lending.forUrl(seedUrl)) {
                final List<String> all = new ArrayList<>(List.of("git"));
                all.addAll(lease.arguments());
                all.addAll(List.of("clone", "--bare", "--end-of-options", seedUrl, mirror.toString()));
                runner.runOrFail(Command.of(all).withEnvironment(lease.environment()));
            } catch (CommandException ex) {
                throw new GateException(ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
            }
        }
        dropApprovedBranches();
        // The agent pushes over HTTP, and git refuses that on a bare repository unless told the
        // repository is meant to be served.
        gitIn("config", "http.receivepack", "true");
        gitIn("config", "receive.denyCurrentBranch", "ignore");
    }

    /**
     * Brings the mirror's branches up to the upstream's, before a task starts from them.
     * <p>
     * <strong>A mirror is cloned from the upstream once, and approved work goes back to the upstream, never into the
     * mirror.</strong> So without this the mirror's {@code main} stayed where it was cloned: the next task started from
     * the old base, without the change approved before it, and its own forward was then refused as no fast-forward
     * (found walking a real repository, 2026-10-01). The branches are taken as the upstream has them; what tasks pushed
     * waits under {@code refs/sokar/incoming/}, which this does not touch.
     * <p>
     * Only where the mirror was cloned from the upstream it forwards to, and never in an {@code offline} project,
     * which reaches no upstream. Never fatal: a task can still start from what the mirror holds, and is told so.
     *
     * @return "" when it is up to date or has nothing to follow; otherwise what could not be done, to say.
     */
    public String refresh() {
        if (mode == GateMode.OFFLINE || upstreamUrl == null || !upstreamUrl.equals(seedUrl)
                || !Files.isDirectory(mirror.resolve("objects"))) {
            return "";
        }
        try (GitCredentials.Lease lease = lending.forUrl(upstreamUrl)) {
            gitWith(lease, "fetch", "--quiet", "--prune", "--end-of-options", upstreamUrl, "+refs/heads/*:refs/heads/*",
                    "^" + APPROVED + "*");
            dropApprovedBranches();
            return "";
        } catch (GateException ex) {
            return "the mirror could not be brought up to the upstream's branches, so this task starts from what it"
                    + " holds, which may be behind: " + ex.getMessage();
        }
    }

    /**
     * Returns where the mirror was cloned from.
     * <p>
     * Recorded by {@code git clone --bare} itself, so it is the mirror that answers rather than
     * any configuration that might since have changed.
     *
     * @return The seed URL, or {@code null} for a mirror that was created empty.
     */
    /**
     * Returns the mirror's branches and the commit each names, to tell what a refresh moved.
     *
     * @return Branch name to commit; empty for a mirror with no branch yet.
     */
    public java.util.Map<String, String> branches() {
        final java.util.Map<String, String> branches = new java.util.LinkedHashMap<>();
        if (!Files.isDirectory(mirror.resolve("objects"))) {
            return branches;
        }
        for (final String line : gitIn("for-each-ref", "--format=%(refname:short) %(objectname)", "refs/heads")
                .standardOutput().lines().toList()) {
            final int space = line.indexOf(' ');
            if (space > 0) {
                branches.put(line.substring(0, space), line.substring(space + 1).strip());
            }
        }
        return branches;
    }

    /** Where approved work lands in a checkout that is its own source: never brought back as a branch of the gate. */
    static final String APPROVED = "refs/heads/sokar/";

    /**
     * Removes the branches a source holds only because work was approved into it: brought back, a task saw its own
     * approved work again as {@code sokar/sokar/<task>}.
     */
    private void dropApprovedBranches() {
        for (final String ref : gitIn("for-each-ref", "--format=%(refname)", APPROVED).standardOutput().lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList()) {
            gitIn("update-ref", "-d", ref);
        }
    }

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
        // As git names them: a name ending in a Unicode space is not the name without it.
        result.standardOutput().lines()
                .filter(line -> line.startsWith(INCOMING))
                // An online task's ref was passed on as it was pushed and waits for nobody; its rescue never went on.
                .filter(line -> mode != GateMode.ONLINE || line.endsWith(RESCUED))
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
                "--format=%(refname)\t%(objectname)\t%(committerdate:unix)\t%(objecttype)\t%(contents:subject)",
                "--sort=committerdate", INCOMING);
        final List<PendingPush> pushes = new ArrayList<>();
        result.standardOutput().lines().forEach(line -> {
            final String[] fields = line.split("\t", 5);
            if (fields.length == 5 && fields[0].startsWith(INCOMING)
                    && (mode != GateMode.ONLINE || fields[0].endsWith(RESCUED))) {
                // Listed whatever the agent pushed: receive-pack refuses a tag or a blob only under refs/heads, and
                // one that made this throw took the review queue away from every task of the project.
                final String subject = "commit".equals(fields[3]) ? fields[4]
                        : "(a " + fields[3] + ", not a commit: nothing to review or approve)";
                pushes.add(new PendingPush(fields[0].substring(INCOMING.length()), fields[1], subject,
                        committed(fields[2])));
            }
        });
        return List.copyOf(pushes);
    }

    /** A commit's time as git gave it; the epoch when there is none, or one no instant can hold. */
    private static java.time.Instant committed(String seconds) {
        try {
            return java.time.Instant.ofEpochSecond(Long.parseLong(seconds.strip()));
        } catch (NumberFormatException | java.time.DateTimeException ex) {
            return java.time.Instant.EPOCH;
        }
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
        return gitIn(join(List.of("diff"), List.of(), compared(against, name))).standardOutput();
    }

    /**
     * Returns what a review compares: from where the task's work and the branch last met, so what the branch gained
     * since - a later task's start brings the mirror up to the upstream - is not shown as the task removing it. Two
     * histories that never met are compared as they stand.
     */
    private List<String> compared(final String against, final String name) {
        return runner.run(Command.of(List.of("git", "--git-dir", mirror.toString(), "merge-base", against,
                INCOMING + name))).successful() ? List.of(against + "..." + INCOMING + name)
                        : List.of(against, INCOMING + name);
    }

    /**
     * Returns what an incoming ref would change, ranked by what reading it is worth, and the patch in
     * that order.
     * <p>
     * Over the same range as {@link #review}. Renames are shown as a removal and an addition, so a file
     * moved into a place that runs - a workflow directory - is ranked by where it arrives.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param against Ref to compare against, or {@code null} to show the ref's own last commit.
     * @return The ranked files and the patch.
     */
    public ReviewRanking.Review rankedReview(String name, @Nullable String against) {
        requirePending(name);
        final boolean compare = against != null && !against.isBlank() && resolves(against);
        final List<String> verb = compare ? List.of("diff", "--no-renames") : List.of("show", "--format=", "--no-renames");
        final List<String> refs = compare && against != null ? compared(against, name) : List.of(INCOMING + name);
        // Read with -z: without it git quotes a path holding a byte beyond ASCII, a '"' or a tab, and the quoted name
        // matched no dangerous place - a workflow ranked ordinary.
        final Map<String, int[]> counts = numstat(gitIn(join(verb, List.of("--numstat", "-z"), refs)).standardOutput());
        final Set<String> beyondWhitespace = numstat(gitIn(join(verb,
                List.of("--numstat", "-z", "-w", "--ignore-blank-lines"), refs)).standardOutput()).keySet();
        final List<ReviewRanking.Change> changes = new ArrayList<>();
        final String[] raw = gitIn(join(verb, List.of("--raw", "-z", "--no-abbrev"), refs)).standardOutput().split("\0");
        for (int at = 0; at + 1 < raw.length; at += 2) {
            // ":<old mode> <new mode> <old blob> <new blob> <status>" NUL "<path>" NUL - no renames, so one path each.
            final String line = raw[at];
            if (!line.startsWith(":")) {
                continue;
            }
            final String[] fields = line.substring(1).split(" ");
            final String path = raw[at + 1];
            final String status = fields[4];
            final int[] count = counts.getOrDefault(path, new int[] { 0, 0 });
            // Whitespace alone: something changed, and nothing is left of it once whitespace is ignored.
            final boolean whitespaceOnly = "M".equals(status) && fields[0].equals(fields[1])
                    && (count[0] > 0 || count[1] > 0) && !beyondWhitespace.contains(path);
            final String head = "D".equals(status) || changes.size() >= HEADS_READ ? null : head(fields[3]);
            changes.add(new ReviewRanking.Change(path, status, fields[0], fields[1], count[0], count[1],
                    whitespaceOnly, head));
        }
        final List<ReviewRanking.File> files = ReviewRanking.rank(changes);
        final Path order;
        try {
            order = Files.createTempFile("sokar-review-order", ".txt");
            Files.writeString(order, ReviewRanking.orderFile(files), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new GateException("could not order the review: " + ex.getMessage(), ex);
        }
        try {
            final String patch = gitIn(join(verb, List.of("--patch", "-O" + order), refs)).standardOutput();
            return new ReviewRanking.Review(files, patch);
        } finally {
            try {
                Files.deleteIfExists(order);
            } catch (IOException ex) {
                // A leftover order file names paths the operator is about to read anyway; not worth failing for.
            }
        }
    }

    /** How many files' heads are read to see whether they say they were generated. */
    private static final int HEADS_READ = 500;

    private String head(String blob) {
        final String content = gitIn("cat-file", "-p", blob).standardOutput();
        return content.length() > 2048 ? content.substring(0, 2048) : content;
    }

    private static Map<String, int[]> numstat(String output) {
        final Map<String, int[]> counts = new java.util.LinkedHashMap<>();
        // With -z each entry is "<added>\t<deleted>\t<path>" ended by a NUL, the path as it is.
        for (final String line : output.split("\0")) {
            final String[] fields = line.split("\t", 3);
            if (fields.length == 3) {
                counts.put(fields[2], new int[] { "-".equals(fields[0]) ? -1 : Integer.parseInt(fields[0]),
                        "-".equals(fields[1]) ? -1 : Integer.parseInt(fields[1]) });
            }
        }
        return counts;
    }

    private static String[] join(List<String> verb, List<String> options, List<String> refs) {
        final List<String> all = new ArrayList<>(verb);
        all.addAll(options);
        all.addAll(refs);
        return all.toArray(String[]::new);
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
     * Materialises an incoming ref into a working copy somebody can open.
     * <p>
     * <strong>This exists so the safe way is also the convenient one.</strong> The unsafe path -
     * fetch the agent's branch into your own checkout and push it - is two ordinary git commands
     * and needs no privilege, so nothing prevents it. What can be changed is which path is easier,
     * and an accident of convenience is only ever fixed by convenience.
     * <p>
     * <strong>Safe by construction rather than by warning:</strong>
     * <ul>
     * <li><strong>The only remote is the mirror.</strong> There is nowhere to push except back to
     *     the gate, so a push from here cannot reach the upstream by mistake - it is not a
     *     restriction that can be forgotten, it is an address that is not there.</li>
     * <li><strong>{@code core.hooksPath} points at an empty directory</strong>, so no hook runs in
     *     this copy whatever put one there. An agent's work is about to be opened in an editor
     *     that runs git on it, and a hook is code that runs without being read.</li>
     * <li><strong>It is a detached checkout of the ref</strong>, not a branch, so nothing here
     *     looks like work in progress that somebody should continue.</li>
     * </ul>
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param into Directory to create. Must not exist.
     * @throws GateException If the ref is not there, or the copy cannot be made.
     */
    public void checkout(String name, Path into) {

        requirePending(name);
        if (Files.exists(into)) {
            throw new GateException(into + " already exists; a checkout writes a new directory so"
                    + " it cannot quietly mix with something already there");
        }
        final String target = into.toString();
        git("init", "--quiet", target);

        // The mirror, under the name the container's own working copy uses, so the two read the
        // same to anybody who has seen both.
        gitAt(target, "remote", "add", "sokar", mirror.toString());
        gitAt(target, "fetch", "--quiet", "sokar", INCOMING + name);

        // Set before anything is checked out: a hook cannot run in a tree that does not exist yet,
        // and after the checkout would be one command too late.
        final Path noHooks = into.resolve(".git").resolve("sokar-no-hooks");
        try {
            Files.createDirectories(noHooks);
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot make " + noHooks, ex);
        }
        gitAt(target, "config", "core.hooksPath", noHooks.toString());

        gitAt(target, "checkout", "--quiet", "--detach", "FETCH_HEAD");
    }

    private CommandResult gitAt(String directory, String... arguments) {
        final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", directory));
        command.addAll(List.of(arguments));
        return runner.runOrFail(Command.of(command));
    }

    // Every address the project file names goes to git after '--end-of-options': written as '--upload-pack=<command>',
    // an upstream reached 'git fetch' as an option and ran that command on the host. The project file refuses such a
    // value; this is so that no other way in can.

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
        approve(name, branch, null);
    }

    /**
     * Forwards the commit a person reviewed to the upstream.
     * <p>
     * The task's gate stays up while a person reads, so the ref can move under them: forwarding the ref as it stood
     * at the approval sent a push made after the review, unseen, and deleting it afterwards destroyed one that landed
     * in between. The commit is pinned first; with {@code reviewed} it must be the one the person read, and the ref
     * is removed only while it still points at what went.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to push to.
     * @param reviewed The commit the person reviewed, or {@code null} for whatever the ref holds now.
     * @throws GateException If the mode forbids forwarding, no upstream is configured, or the ref moved since the
     *         review.
     */
    public void approve(String name, String branch, @org.jspecify.annotations.Nullable String reviewed) {
        if (!mode.canForward()) {
            throw new GateException("This project's security class is offline, so nothing is"
                    + " forwarded upstream");
        }
        if (upstreamUrl == null) {
            throw new GateException("No upstream is configured for this project");
        }
        requirePending(name);
        final String commit = reviewedCommit(name, reviewed);
        // The one command here that reaches the forge. It ran with nothing: an approved change
        // could not be forwarded to a private upstream unless the account happened to have a key
        // of its own lying about, which is exactly the arrangement the vault exists to replace.
        try (GitCredentials.Lease lease = lending.forUrl(upstreamUrl)) {
            refuseEarlierWork(lease, upstreamUrl, branch, commit);
            gitWith(lease, "push", "--end-of-options", upstreamUrl, commit + ":refs/heads/" + branch);
        }
        removeIfStill(name, commit);
    }

    /**
     * Returns the first of {@code <branch>-2}, {@code -3}, ... the upstream does not hold, for a person whose approve
     * met earlier work: a suggestion, never a force over the branch that is taken.
     *
     * @param taken The branch that holds earlier work.
     * @return The next free branch; {@code <taken>-2} when the upstream cannot be asked.
     */
    public String nextFreeBranch(String taken) {
        final String upstream = upstreamUrl;
        if (upstream == null) {
            return taken + "-2";
        }
        try (GitCredentials.Lease lease = lending.forUrl(upstream)) {
            final String listed = gitWith(lease, "ls-remote", "--heads", "--end-of-options", upstream)
                    .standardOutput();
            for (int next = 2; next < 100; next++) {
                final String candidate = taken + "-" + next;
                if (!listed.contains("refs/heads/" + candidate + "\n") && !listed.endsWith("refs/heads/" + candidate)) {
                    return candidate;
                }
            }
        } catch (GateException ex) {
            return taken + "-2";
        }
        return taken + "-2";
    }

    /**
     * Refuses by name a branch that holds work the reviewed commit did not grow from, before git refuses it as a
     * non-fast-forward in its own words. A branch that is not there, or that the work grew from, passes.
     *
     * @param lease The upstream's credential.
     * @param upstream The upstream.
     * @param branch The branch to push to.
     * @param commit The reviewed commit.
     * @throws GateException.BranchExists When it holds earlier work.
     */
    private void refuseEarlierWork(GitCredentials.Lease lease, String upstream, String branch, String commit) {
        final String listed = gitWith(lease, "ls-remote", "--end-of-options", upstream, "refs/heads/" + branch)
                .standardOutput().strip();
        if (listed.isEmpty()) {
            return;
        }
        final String at = listed.split("\\s+")[0];
        if (at.equals(commit)) {
            return;
        }
        gitWith(lease, "fetch", "--quiet", "--end-of-options", upstream, "refs/heads/" + branch);
        final CommandResult grewFrom = runner.run(Command.of("git", "--git-dir", mirror.toString(), "merge-base",
                "--is-ancestor", at, commit));
        if (!grewFrom.successful()) {
            throw new GateException.BranchExists(branch, at);
        }
    }

    /**
     * Forgets the ref a task left at its gate: for an {@code online} task, whose push went on and so waits for nobody,
     * when it is removed or a new task of its name starts. Its rescue stays: that work never went on, and waits. A new task of the same name otherwise
     * met the old ref, its plain push was refused with git's "fetch first", and an agent that followed the hint pulled
     * a removed task's work into its own.
     *
     * @param name The task's name, without the namespace prefix.
     */
    public void forget(String name) {
        if (resolves(INCOMING + name)) {
            gitIn("update-ref", "-d", INCOMING + name);
        }
    }

    /**
     * Returns the commit and subject of a task's work that waits at the gate, for a start that would otherwise build a
     * new task of the same name on top of it.
     *
     * @param name The task's name, without the namespace prefix.
     * @return {@code "<commit> <subject>"}, or {@code null} when nothing of that name waits.
     */
    public @org.jspecify.annotations.Nullable String waiting(String name) {
        if (mode == GateMode.ONLINE || !resolves(INCOMING + name)) {
            return null;
        }
        return gitIn("log", "-1", "--format=%h %s", INCOMING + name).standardOutput().strip();
    }

    /**
     * A task's work that waits at the gate, in full.
     *
     * @param commit The commit it holds.
     * @param subject Its subject line.
     */
    public record Waiting(String commit, String subject) {
    }

    /**
     * Returns the work of a task's name that waits at the gate, for a start refused by name.
     *
     * @param name The task's name, without the namespace prefix.
     * @return The work, or {@code null} when nothing of that name waits.
     */
    public @org.jspecify.annotations.Nullable Waiting waitingWork(String name) {
        if (mode == GateMode.ONLINE || !resolves(INCOMING + name)) {
            return null;
        }
        final String[] parts = gitIn("log", "-1", "--format=%H%n%s", INCOMING + name).standardOutput().strip()
                .split("\n", 2);
        return new Waiting(parts[0], parts.length > 1 ? parts[1] : "");
    }

    /**
     * Gives the upstream a task's work at once, as an {@code online} task's gate does while the agent's push runs.
     * <p>
     * With force, onto the task's own branch, which only the task writes: an agent that rebases pushes over its own
     * earlier work, and nothing of anybody else's is under that name. With the key the host lends for the upstream; the
     * container never holds it.
     *
     * @param branch The branch at the upstream, without {@code refs/heads/}.
     * @param commit What it is to point at.
     * @return {@code null} when the upstream took it, else what it said.
     */
    public @org.jspecify.annotations.Nullable String passOn(String branch, String commit) {
        if (mode != GateMode.ONLINE) {
            return "this project's security class does not pass a push on; it waits for approval";
        }
        if (upstreamUrl == null) {
            return "no upstream is configured for this project";
        }
        try (GitCredentials.Lease lease = lending.forUrl(upstreamUrl)) {
            gitWith(lease, "push", "--force", "--end-of-options", upstreamUrl, commit + ":refs/heads/" + branch);
            return null;
        } catch (GateException ex) {
            return ex.getMessage();
        }
    }

    /**
     * Returns the commit an incoming ref holds, refusing when it is not the one a person reviewed.
     *
     * @param name Incoming ref name.
     * @param reviewed The reviewed commit, or {@code null}.
     * @return The full commit id.
     */
    private String reviewedCommit(String name, @org.jspecify.annotations.Nullable String reviewed) {
        final String now = gitIn("rev-parse", "--verify", INCOMING + name + "^{commit}").standardOutput().strip();
        if (reviewed != null && !reviewed.isBlank() && !reviewed.strip().matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            // In full: the seven characters a listing shows can be met by another commit the agent grinds out.
            throw new GateException("name the reviewed commit in full, as 'gate review' shows it: '" + reviewed.strip()
                    + "' is not a full commit id");
        }
        if (reviewed != null && !reviewed.isBlank() && !now.equals(reviewed.strip())) {
            throw new GateException.MovedSinceReview(name, reviewed.strip(), now);
        }
        return now;
    }

    /** Removes an incoming ref only while it still holds what went, so a push that came after stays waiting. */
    private void removeIfStill(String name, String commit) {
        try {
            gitIn("update-ref", "-d", INCOMING + name, commit);
        } catch (GateException ex) {
            // It moved after the forward: what came after waits for its own review.
        }
    }

    /**
     * Forwards an incoming ref to the upstream as a merge commit signed by the person approving.
     * <p>
     * <strong>For what the project's configuration is made of</strong> - a machine's enrolment, say: every
     * machine accepts a configuration only when its commit is signed with the project's key, which is a
     * person's, never a machine's. Approving otherwise only pushes the reviewed commit, unsigned. Here the
     * reviewed work is merged onto the upstream branch with {@code git merge --no-ff -S}, in a scratch clone,
     * so git signs with the key the person's own git is set up to sign with - an ssh-agent included - and
     * the machine never holds it. A merge that comes out unsigned is not pushed.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to merge onto and push to.
     * @param scratch An empty directory to merge in; removed afterwards by the caller.
     * @throws GateException If the mode forbids forwarding, no upstream is configured, the merge cannot be
     *         made or signed, or the push is refused.
     */
    public void approveSigned(String name, String branch, Path scratch) {
        approveSigned(name, branch, scratch, null);
    }

    /**
     * Forwards the commit a person reviewed as a merge signed by them; see {@link #approve(String, String, String)}
     * for why the commit is pinned.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch Upstream branch to merge onto and push to.
     * @param scratch An empty directory to merge in; removed afterwards by the caller.
     * @param reviewed The commit the person reviewed, or {@code null} for whatever the ref holds now.
     * @throws GateException As {@link #approveSigned(String, String, Path)}, and when the ref moved since the review.
     */
    public void approveSigned(String name, String branch, Path scratch,
            @org.jspecify.annotations.Nullable String reviewed) {
        if (!mode.canForward()) {
            throw new GateException("This project's security class is offline, so nothing is"
                    + " forwarded upstream");
        }
        if (upstreamUrl == null) {
            throw new GateException("No upstream is configured for this project");
        }
        requirePending(name);
        final String commit = reviewedCommit(name, reviewed);
        final String target = scratch.toString();
        git("init", "--quiet", target);
        final Path noHooks = scratch.resolve(".git").resolve("sokar-no-hooks");
        try {
            Files.createDirectories(noHooks);
        } catch (java.io.IOException ex) {
            throw new GateException("Cannot make " + noHooks, ex);
        }
        gitAt(target, "config", "core.hooksPath", noHooks.toString());
        try (GitCredentials.Lease lease = lending.forUrl(upstreamUrl)) {
            gitAtWith(lease, target, "fetch", "--quiet", "--end-of-options", upstreamUrl, "refs/heads/" + branch);
            gitAt(target, "checkout", "--quiet", "-B", branch, "FETCH_HEAD");
            gitAt(target, "fetch", "--quiet", mirror.toString(), INCOMING + name);
            if (!commit.equals(gitAt(target, "rev-parse", "FETCH_HEAD").standardOutput().strip())) {
                throw new GateException("'" + name + "' moved while it was being approved; nothing was forwarded."
                        + " Review it again");
            }
            final CommandResult merged;
            try {
                merged = gitAt(target, "merge", "--no-ff", "-S", "-m", "Merge " + name + ", reviewed and signed",
                        "FETCH_HEAD");
            } catch (CommandException ex) {
                throw new GateException("The merge of '" + name + "' could not be made or signed: "
                        + ex.getMessage() + ". Signing uses your own git: set user.signingkey (and gpg.format ssh"
                        + " for an ssh key) where you approve", ex);
            }
            // Whether it carries a signature at all - not whether this machine can verify it: '%G?' answers "N" for an
            // ssh signature whenever no allowed-signers file is configured, which is the ordinary case here.
            final boolean signed = gitAt(target, "cat-file", "commit", "HEAD").standardOutput().lines()
                    .anyMatch(line -> line.startsWith("gpgsig "));
            if (!signed) {
                throw new GateException("The merge of '" + name + "' came out unsigned, so it is not pushed; set"
                        + " user.signingkey where you approve (" + merged.standardOutput().strip() + ")");
            }
            gitAtWith(lease, target, "push", "--end-of-options", upstreamUrl, "HEAD:refs/heads/" + branch);
        }
        removeIfStill(name, commit);
    }

    private CommandResult gitAtWith(GitCredentials.Lease lease, String directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(lease.arguments());
        all.addAll(List.of("-C", directory));
        all.addAll(List.of(arguments));
        try {
            return runner.runOrFail(Command.of(all).withEnvironment(lease.environment()));
        } catch (CommandException ex) {
            throw new GateException(ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
        }
    }

    /** The largest bundle {@link #bundle} hands out: a varlink reply is one message, and base64 adds a third. */
    public static final long BUNDLE_LIMIT = 16L * 1024 * 1024;

    /**
     * Writes pending work as a git bundle, for somebody who merges it elsewhere - a person who signs the merge on
     * their own computer. Only what the branch in the mirror does not have yet, when the mirror has it; the whole
     * history otherwise.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch The branch it is to be merged onto.
     * @param into The bundle file to write.
     * @return The bundle's size in bytes.
     * @throws GateException If nothing is pending under that name, or the bundle cannot be written.
     */
    public long bundle(String name, String branch, Path into) {
        requirePending(name);
        final List<String> arguments = new ArrayList<>(List.of("bundle", "create", into.toString(), INCOMING + name));
        if (resolves("refs/heads/" + branch)) {
            arguments.addAll(List.of("--not", "refs/heads/" + branch));
        }
        gitIn(arguments.toArray(String[]::new));
        try {
            return Files.size(into);
        } catch (java.io.IOException ex) {
            throw new GateException("The bundle of '" + name + "' was not written", ex);
        }
    }

    /**
     * What {@link #landed} found.
     *
     * @param landed Whether the pending work is on the branch, and the pending ref was cleared.
     * @param commit The pending commit.
     * @param detail Why not, in words; empty when it landed.
     */
    public record Landing(boolean landed, String commit, String detail) {
    }

    /**
     * Clears pending work that somebody merged elsewhere - a person's signed merge pushed from their own
     * computer - once, and only once, the upstream's branch holds it.
     * <p>
     * Never recorded as rejected, which is what {@link #reject} says: the work went, just not through here.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param branch The upstream branch it was merged onto.
     * @return Whether it landed, and why not when it did not.
     * @throws GateException If nothing is pending under that name.
     */
    public Landing landed(String name, String branch) {
        requirePending(name);
        final String commit = gitIn("rev-parse", INCOMING + name).standardOutput().strip();
        if (upstreamUrl == null) {
            return new Landing(false, commit, "no upstream is configured for this project, so nothing can have landed");
        }
        try (GitCredentials.Lease lease = lending.forUrl(upstreamUrl)) {
            gitWith(lease, "fetch", "--quiet", "--end-of-options", upstreamUrl, "refs/heads/" + branch);
        } catch (GateException ex) {
            return new Landing(false, commit, "the upstream's " + branch + " could not be fetched: " + ex.getMessage());
        }
        final CommandResult ancestor = runner.run(Command.of("git", "--git-dir", mirror.toString(), "merge-base",
                "--is-ancestor", commit, "FETCH_HEAD"));
        if (!ancestor.successful()) {
            return new Landing(false, commit, "it is not on the upstream's " + branch + " yet: push the merge first,"
                    + " or it went to another branch");
        }
        gitIn("update-ref", "-d", INCOMING + name);
        return new Landing(true, commit, "");
    }

    /**
     * Discards an incoming ref without forwarding it.
     *
     * @param name Incoming ref name, without the namespace prefix.
     */
    public void reject(String name) {
        reject(name, false, false);
    }

    /**
     * Discards an incoming ref without forwarding it.
     * <p>
     * <strong>Refused when the upstream already has the work.</strong> A push made by hand does
     * what {@code approve} does to the upstream and not what it does to the queue, so the ref
     * stays behind. Somebody tidying up then marks it discarded while the code is live - the
     * record says the opposite of what happened, which is the dangerous one of the three things a
     * hand push breaks. Whoever means it says {@code force}.
     *
     * @param name Incoming ref name, without the namespace prefix.
     * @param alreadyUpstream Whether the upstream is known to have this work.
     * @param force Whether to discard it anyway.
     * @throws GateException If it is upstream and force was not given.
     */
    public void reject(String name, boolean alreadyUpstream, boolean force) {
        if (alreadyUpstream && !force) {
            throw new GateException("'" + name + "' is already on the upstream, so discarding it"
                    + " would record the opposite of what happened. Somebody pushed it by hand"
                    + " rather than approving it here. Use force to clear the ref anyway.");
        }
        gitIn("update-ref", "-d", INCOMING + name);
    }

    /**
     * Removes the mirror, so a restore has somewhere to write.
     * <p>
     * <strong>Irreversible, and the caller has to have earned it.</strong> Unreviewed pushes exist
     * only here - not on the upstream, not in a workspace, not in the bundle - so this destroys the
     * only copy of whatever was waiting. {@link #restore(Path)} deliberately refuses to write over
     * a mirror rather than calling this itself.
     */
    public void deleteMirror() {
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
        return gitWith(GitCredentials.Lease.EMPTY, arguments);
    }

    private CommandResult gitWith(GitCredentials.Lease lease, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        // Before the verb, which is where git takes '-c'. Never a secret: it names a vault entry.
        all.addAll(lease.arguments());
        all.addAll(List.of("--git-dir", mirror.toString()));
        all.addAll(List.of(arguments));
        try {
            return runner.runOrFail(Command.of(all).withEnvironment(lease.environment()));
        } catch (CommandException ex) {
            throw new GateException(ex.getMessage() == null ? "git failed" : ex.getMessage(), ex);
        }
    }
}
