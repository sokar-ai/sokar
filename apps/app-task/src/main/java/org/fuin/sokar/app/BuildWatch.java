package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.fuin.sokar.build.api.Build;
import org.fuin.sokar.build.api.BuildReaderException;
import org.fuin.sokar.build.api.BuildRefused;
import org.jspecify.annotations.Nullable;

/**
 * Follows every commit an {@code online} task pushes to its branch and hands each build's verdict into the task as it
 * changes, and a failing job's log beside it. The task asks nothing and holds no forge credential: this runs on the
 * host, as a helper of the task's launch.
 * <p>
 * A watch ends at a verdict that will not change, at {@link #DEADLINE}, or when the task stops.
 */
public final class BuildWatch {

    /** How often the forge is asked: often enough that a verdict arrives soon, seldom enough for a shared limit. */
    static final Duration POLL = Duration.ofSeconds(20);

    /** How long one commit's build is waited for. */
    static final Duration DEADLINE = Duration.ofHours(2);

    /** How long a forge may take to show a build of a commit just pushed before "no build" is said. */
    static final Duration GRACE = Duration.ofMinutes(2);

    /** How long to wait after a refusal a person has to fix: a replaced credential is then used without a restart. */
    static final Duration REFUSED = Duration.ofMinutes(10);

    private final Forge forge;

    private final Delivery delivery;

    private final TaskBuilds builds;

    private final String container;

    private final String run;

    private final String branch;

    private final Path scratch;

    private final Clock clock;

    private final Sleeper sleeper;

    private final java.util.function.Consumer<String> trouble;

    /** What {@link #trouble} was told last; {@code null} before the first round, so a clean start is said too. */
    private @Nullable String troubleSaid;

    /** The commits being watched, by sha, oldest push first. */
    private final Map<String, Watched> watched = new LinkedHashMap<>();

    /** Where the branch pointed when last asked; a change is a push. */
    private @Nullable String head;

    /**
     * The forge, as the watch asks it: the reader and the token behind it.
     */
    public interface Forge {

        /**
         * Returns where the task's branch points.
         *
         * @param branch The branch.
         * @return The sha; "" when the forge has no such branch.
         * @throws BuildRefused When the forge would not answer.
         */
        String head(String branch);

        /**
         * Returns what a commit's build did.
         *
         * @param commit The sha.
         * @return The build.
         * @throws BuildRefused When the forge would not answer.
         */
        Build look(String commit);
    }

    /**
     * Sokar cannot ask the forge yet, for a reason on this machine rather than the forge's: the vault is shut, or
     * does not hold the entry. The task is told why, and the watch asks again at the next round.
     */
    public static final class NotNow extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param why What a person has to do.
         */
        public NotNow(final String why) {
            super(why);
        }
    }

    /**
     * Hands a file into the task, as Sokar.
     */
    @FunctionalInterface
    public interface Delivery {

        /**
         * Hands it in.
         *
         * @param name Its name in {@code /sokar/files}.
         * @param file Its content.
         * @return What the task holds now.
         * @throws IOException When it could not be placed.
         */
        HandIns.HandedFile give(String name, Path file) throws IOException;
    }

    /**
     * Waits, so a test need not.
     */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * Waits.
         *
         * @param duration How long.
         * @throws InterruptedException When the watch is to end.
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    /** One commit being watched, and the verdict the task was last given for it. */
    private static final class Watched {

        private final Instant pushed;

        private @Nullable Build told;

        private Instant since;

        Watched(final Instant pushed) {
            this.pushed = pushed;
            this.since = pushed;
        }
    }

    /**
     * Constructor.
     *
     * @param forge What is asked.
     * @param delivery How a file reaches the task.
     * @param builds Where verdicts are written down.
     * @param container The task.
     * @param run Its current run's container id.
     * @param branch The branch it pushes to.
     * @param scratch A directory of the task's own state, for the files before they are handed in.
     * @param clock What time it is.
     * @param sleeper How to wait.
     * @param trouble Told what keeps the watch from asking the forge each time it changes, and "" once it asks again.
     */
    public BuildWatch(final Forge forge, final Delivery delivery, final TaskBuilds builds, final String container,
            final String run, final String branch, final Path scratch, final Clock clock, final Sleeper sleeper,
            final java.util.function.Consumer<String> trouble) {
        this.forge = forge;
        this.delivery = delivery;
        this.builds = builds;
        this.container = container;
        this.run = run;
        this.branch = branch;
        this.scratch = scratch;
        this.clock = clock;
        this.sleeper = sleeper;
        this.trouble = trouble;
    }

    /**
     * Watches until the task stops.
     *
     * @param running Whether the task still runs, asked before every round.
     * @throws InterruptedException When the watch is to end.
     */
    public void run(final BooleanSupplier running) throws InterruptedException {
        resume();
        while (running.getAsBoolean()) {
            sleeper.sleep(round());
        }
    }

    /**
     * Asks the forge once about the branch and every commit being watched, and hands in what changed.
     *
     * @return How long to wait before the next round.
     */
    Duration round() {
        try {
            follow(forge.head(branch));
            for (final Map.Entry<String, Watched> each : List.copyOf(watched.entrySet())) {
                look(each.getKey(), each.getValue());
            }
            troubled("");
            return POLL;
        } catch (BuildRefused ex) {
            return refused(ex);
        } catch (NotNow ex) {
            troubled(String.valueOf(ex.getMessage()));
            tellAll(Build.unknown(String.valueOf(ex.getMessage())));
            return POLL;
        } catch (BuildReaderException ex) {
            // The installation, not the forge: every commit waiting says so, and the watch tries again later.
            troubled("Sokar cannot read builds: " + ex.getMessage());
            tellAll(Build.unknown("Sokar cannot read builds: " + ex.getMessage()));
            return REFUSED;
        }
    }

    /**
     * Says what keeps the watch from asking, once each time it changes. Beside the verdicts, because before the first
     * push no commit is followed and a verdict would reach nobody: the vault shut at a task's start was said nowhere.
     */
    private void troubled(final String why) {
        if (!why.equals(troubleSaid)) {
            troubleSaid = why;
            trouble.accept(why);
        }
    }

    /** Takes up the commits this run already watched, so a resumed task is not told twice or told nothing. */
    private void resume() {
        String last = null;
        try {
            for (final TaskBuilds.Seen seen : builds.record(container)) {
                if (!seen.run().equals(run)) {
                    continue;
                }
                last = seen.commit();
                final Watched each = watched.computeIfAbsent(seen.commit(), any -> new Watched(clock.instant()));
                each.told = new Build(seen.verdict(), seen.jobs().stream()
                        .map(job -> new Build.Job(job.name(), job.result())).toList(), seen.detail());
                if (seen.finished()) {
                    watched.remove(seen.commit());
                }
            }
        } catch (IOException ex) {
            // Nothing to take up: the watch starts from where the branch points now.
        }
        head = last;
    }

    /** A head that moved is a push: the first answer is where the branch was before the task pushed anything. */
    private void follow(final String now) {
        if (head == null) {
            head = now;
            return;
        }
        if (!now.isEmpty() && !now.equals(head)) {
            head = now;
            watched.putIfAbsent(now, new Watched(clock.instant()));
        }
    }

    private void look(final String commit, final Watched each) {
        final Build build = forge.look(commit);
        final Instant now = clock.instant();
        if (Build.UNKNOWN.equals(build.verdict()) && now.isBefore(each.pushed.plus(GRACE))) {
            return;
        }
        if (!build.finished() && now.isAfter(each.pushed.plus(DEADLINE))) {
            tell(commit, each, Build.unknown("no verdict within " + DEADLINE.toHours() + " hours; Sokar stopped"
                    + " waiting for this build (last: " + build.verdict() + ")"));
            watched.remove(commit);
            return;
        }
        tell(commit, each, build);
        if (build.finished()) {
            watched.remove(commit);
        }
    }

    private Duration refused(final BuildRefused ex) {
        final String why = switch (ex.reason()) {
            case RATE_LIMITED -> "the forge's rate limit is reached; Sokar asks again when it resets: ";
            case CREDENTIAL_REFUSED -> "the forge refused the credential the project names for builds: ";
            case NO_SUCH_REPOSITORY -> "the forge shows the credential no such repository: ";
            case UNREACHABLE -> "the forge did not answer: ";
        };
        troubled(why + ex.detail());
        tellAll(Build.unknown(why + ex.detail()));
        return switch (ex.reason()) {
            case RATE_LIMITED -> Duration.ofSeconds(Math.max(POLL.toSeconds(), ex.retryAfterSeconds()));
            case CREDENTIAL_REFUSED, NO_SUCH_REPOSITORY -> REFUSED;
            case UNREACHABLE -> POLL;
        };
    }

    private void tellAll(final Build build) {
        for (final Map.Entry<String, Watched> each : List.copyOf(watched.entrySet())) {
            tell(each.getKey(), each.getValue(), build);
        }
    }

    /** Hands a verdict in when it differs from the one the task has; the logs first, so the verdict names files. */
    private void tell(final String commit, final Watched each, final Build build) {
        if (each.told != null && each.told.verdict().equals(build.verdict()) && same(each.told.jobs(), build.jobs())
                && each.told.detail().equals(build.detail())) {
            return;
        }
        final Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (each.told == null || !each.told.verdict().equals(build.verdict())) {
            each.since = now;
        }
        final String base = "build-" + commit.substring(0, Math.min(12, commit.length()));
        try {
            Files.createDirectories(scratch);
            final List<TaskBuilds.Job> jobs = new java.util.ArrayList<>();
            for (final Build.Job job : build.jobs()) {
                final byte[] tail = job.log();
                TaskBuilds.Log log = null;
                if (tail != null) {
                    final HandIns.HandedFile handed = handIn(base + "-" + (jobs.size() + 1) + ".log", tail);
                    log = new TaskBuilds.Log(handed.name(), handed.bytes(), handed.sha256());
                }
                jobs.add(new TaskBuilds.Job(job.name(), job.result(), log));
            }
            final TaskBuilds.Seen seen = new TaskBuilds.Seen(commit, build.verdict(), jobs, each.since.toString(),
                    build.detail(), run);
            handIn(base + ".txt", text(seen).getBytes(StandardCharsets.UTF_8));
            builds.write(container, seen);
            each.told = build;
        } catch (IOException | HandIns.Refused ex) {
            // Not told: the next round tries again, since 'told' still holds what the task has.
        }
    }

    /** The same jobs with the same results: a log's bytes are not compared, a job's end does not change. */
    private static boolean same(final List<Build.Job> told, final List<Build.Job> now) {
        return told.stream().map(job -> job.name() + "\u0000" + job.result()).toList()
                .equals(now.stream().map(job -> job.name() + "\u0000" + job.result()).toList());
    }

    /** Hands content in through a file of the task's own state that is gone again after: Sokar keeps no copy. */
    private HandIns.HandedFile handIn(final String name, final byte[] content) throws IOException {
        final Path file = scratch.resolve(name);
        try {
            Files.write(file, content);
            return delivery.give(name, file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /**
     * Returns what the task reads about one build.
     *
     * @param seen The verdict.
     * @return Lines of {@code key: value}.
     */
    static String text(final TaskBuilds.Seen seen) {
        final StringBuilder text = new StringBuilder();
        text.append("commit: ").append(seen.commit()).append('\n');
        text.append("verdict: ").append(seen.verdict()).append('\n');
        for (final TaskBuilds.Job job : seen.jobs()) {
            text.append("job: ").append(job.name()).append(" - ").append(job.result());
            if (job.log() != null) {
                text.append(" - /sokar/files/").append(job.log().name());
            }
            text.append('\n');
        }
        text.append("since: ").append(seen.since()).append('\n');
        if (!seen.detail().isEmpty()) {
            text.append("detail: ").append(seen.detail()).append('\n');
        }
        return text.toString();
    }
}
