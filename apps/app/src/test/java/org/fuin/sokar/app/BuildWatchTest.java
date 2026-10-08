package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.sokar.build.api.Build;
import org.fuin.sokar.build.api.BuildRefused;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildWatchTest {

    private static final String TASK = "sokar-demo-shell-1";

    private static final String RUN = "0123456789ab";

    private static final String BASE = "b".repeat(40);

    private static final String FIRST = "1".repeat(40);

    private static final String SECOND = "2".repeat(40);

    @TempDir
    Path dir;

    private TaskPaths paths;

    private TaskBuilds builds;

    private Instant now = Instant.parse("2026-10-06T12:00:00Z");

    /** The forge: where the branch points and what each commit's build is; a refusal replaces every answer. */
    private String head = BASE;

    private final Map<String, Build> forge = new HashMap<>();

    private BuildRefused refusal;

    private BuildWatch.NotNow notNow;

    /** What keeps the watch from asking, each time it changed; "" once it asks again. */
    private final List<String> troubles = new ArrayList<>();

    private final List<String> looked = new ArrayList<>();

    /** What the task was handed, in order, by name, with its content. */
    private final List<Map.Entry<String, String>> handed = new ArrayList<>();

    private final Map<String, String> files = new LinkedHashMap<>();

    @BeforeEach
    void aRunningTask() throws IOException {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        paths = new SokarPaths(xdg, dir.resolve("bin")).tasks();
        Files.createDirectories(paths.containerState(TASK));
        Sidecar.recordId(paths.containerState(TASK), RUN);
        builds = new TaskBuilds(paths);
    }

    private BuildWatch watch() {
        return new BuildWatch(new BuildWatch.Forge() {
            @Override
            public String head(final String branch) {
                assertThat(branch).isEqualTo(TASK);
                refuse();
                return head;
            }

            @Override
            public Build look(final String commit) {
                refuse();
                looked.add(commit);
                return forge.getOrDefault(commit, Build.unknown("no build of " + commit.substring(0, 12) + " yet"));
            }
        }, (name, file) -> {
            final byte[] content = Files.readAllBytes(file);
            final String text = new String(content, StandardCharsets.UTF_8);
            handed.add(Map.entry(name, text));
            files.put(name, text);
            return new HandIns.HandedFile(name, content.length, HandIns.sha256Of(file), now.toString(),
                    HandIns.SOKAR, RUN);
        }, builds, TASK, RUN, TASK, paths.containerState(TASK).resolve("builds"), clock(), duration -> {
            now = now.plus(duration);
        }, troubles::add);
    }

    private void refuse() {
        if (refusal != null) {
            throw refusal;
        }
        if (notNow != null) {
            throw notNow;
        }
    }

    @Test
    void followsOnlyWhatTheTaskPushesAndHandsInEachVerdictAsItChanges() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        assertThat(looked).as("where the branch was before the task pushed is not the task's").isEmpty();

        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();
        watch.round();
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        later(BuildWatch.POLL);
        watch.round();
        watch.round();

        assertThat(handed).extracting(Map.Entry::getKey).as("running once, success once, nothing twice")
                .containsExactly("build-111111111111.txt", "build-111111111111.txt");
        assertThat(files.get("build-111111111111.txt")).contains("commit: " + FIRST).contains("verdict: success")
                .contains("since: 2026-10-06T12:00:20Z");
        assertThat(looked).as("asked while running, twice, and at success; never after it is finished").hasSize(3);
        assertThat(builds.current(TASK)).singleElement().satisfies(seen -> {
            assertThat(seen.verdict()).isEqualTo(Build.SUCCESS);
            assertThat(seen.run()).isEqualTo(RUN);
        });
    }

    @Test
    void handsInAFailuresLogBeforeTheVerdictThatNamesItAndKeepsNoCopy() throws IOException {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.FAILURE, List.of(new Build.Job("Build / unit tests", "failure",
                "MARKER-7f3a the assertion failed\n".getBytes(StandardCharsets.UTF_8)), new Build.Job("Build / docs",
                "skipped")), ""));

        watch.round();

        assertThat(handed).extracting(Map.Entry::getKey)
                .containsExactly("build-111111111111-1.log", "build-111111111111.txt");
        assertThat(files.get("build-111111111111-1.log")).isEqualTo("MARKER-7f3a the assertion failed\n");
        assertThat(files.get("build-111111111111.txt")).contains("verdict: failure")
                .contains("job: Build / unit tests - failure - /sokar/files/build-111111111111-1.log")
                .contains("job: Build / docs - skipped\n");
        assertThat(builds.current(TASK)).singleElement().satisfies(seen -> {
            assertThat(seen.jobs()).hasSize(2);
            assertThat(seen.jobs().get(0).log()).isNotNull();
            assertThat(seen.jobs().get(0).log().bytes()).isEqualTo(33);
            assertThat(seen.jobs().get(0).log().sha256()).hasSize(64);
            assertThat(seen.jobs().get(1).log()).as("a job the forge gave no log for").isNull();
            assertThat(seen.jobs().stream().map(job -> job.asMap().get("log")).toList())
                    .as("the contract names the file").containsExactly("build-111111111111-1.log", "");
        });
        try (Stream<Path> everything = Files.walk(dir)) {
            assertThat(everything.filter(Files::isRegularFile).filter(file -> contains(file, "MARKER-7f3a")))
                    .as("the log's text is nowhere on the host: not in the record, not in the task's state").isEmpty();
        }
    }

    @Test
    void saysNoBuildOnlyOnceTheForgeHadTimeToStartOne() {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;

        watch.round();
        assertThat(handed).as("within the grace a missing build is not news").isEmpty();

        later(BuildWatch.GRACE.plusSeconds(1));
        watch.round();
        assertThat(files.get("build-111111111111.txt")).contains("verdict: unknown").contains("no build of");
    }

    @Test
    void stopsWaitingAtTheDeadlineAndSaysSo() {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();

        later(BuildWatch.DEADLINE.plusSeconds(1));
        watch.round();
        looked.clear();
        watch.round();

        assertThat(files.get("build-111111111111.txt")).contains("verdict: unknown")
                .contains("no verdict within 2 hours").contains("last: running");
        assertThat(looked).as("nothing is waited for after the deadline").isEmpty();
    }

    @Test
    void aRateLimitARefusedCredentialAndAMissingRepositoryAreToldApartAndWaitedOutDifferently() {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();

        refusal = new BuildRefused(BuildRefused.Reason.RATE_LIMITED, "used up", 300);
        assertThat(watch.round()).as("until the limit resets").isEqualTo(Duration.ofSeconds(300));
        final String limited = files.get("build-111111111111.txt");
        refusal = new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, "401");
        assertThat(watch.round()).isEqualTo(BuildWatch.REFUSED);
        final String credential = files.get("build-111111111111.txt");
        refusal = new BuildRefused(BuildRefused.Reason.NO_SUCH_REPOSITORY, "404");
        watch.round();
        final String repository = files.get("build-111111111111.txt");

        assertThat(limited).contains("verdict: unknown").contains("rate limit").doesNotContain("credential");
        assertThat(credential).contains("refused the credential");
        assertThat(repository).contains("no such repository");

        refusal = null;
        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        watch.round();
        assertThat(files.get("build-111111111111.txt")).as("the watch goes on once the forge answers")
                .contains("verdict: success");
    }

    @Test
    void aRefusalBeforeTheFirstPushIsSaidWhereAPersonLooksAndClearedOnceTheForgeAnswers() {

        // Nothing was pushed yet, so no commit was followed, and every refusal
        // went to the commits followed - none. The helper's log stayed empty and the task's view said nothing wrong.
        final BuildWatch watch = watch();
        refusal = new BuildRefused(BuildRefused.Reason.CREDENTIAL_REFUSED, "401");
        watch.round();
        assertThat(troubles).as("said before anything was pushed").singleElement().asString()
                .contains("refused the credential").contains("401");

        watch.round();
        assertThat(troubles).as("said once while it lasts").hasSize(1);

        refusal = null;
        watch.round();
        assertThat(troubles).as("taken back once the forge answers").last().isEqualTo("");

        notNow = new BuildWatch.NotNow("the vault is shut");
        watch.round();
        assertThat(troubles).last().isEqualTo("the vault is shut");
    }

    @Test
    void watchesEveryPushAndListsTheNewestFirst() {
        final BuildWatch watch = watch();
        watch.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        watch.round();
        head = SECOND;
        forge.put(SECOND, new Build(Build.QUEUED, ""));
        watch.round();
        forge.put(FIRST, new Build(Build.CANCELLED, ""));
        watch.round();

        assertThat(builds.current(TASK)).extracting(TaskBuilds.Seen::commit, TaskBuilds.Seen::verdict)
                .as("in the order of the pushes, newest first, each with its newest verdict")
                .containsExactly(org.assertj.core.groups.Tuple.tuple(SECOND, Build.QUEUED),
                        org.assertj.core.groups.Tuple.tuple(FIRST, Build.CANCELLED));
    }

    @Test
    void aResumedWatchTakesUpWhatItWatchedAndTellsNothingTwice() throws Exception {
        final BuildWatch before = watch();
        before.round();
        head = FIRST;
        forge.put(FIRST, new Build(Build.RUNNING, ""));
        before.round();
        handed.clear();

        final BuildWatch after = watch();
        final int[] rounds = {0};
        after.run(() -> rounds[0]++ < 1);
        assertThat(handed).as("running was told before the restart").isEmpty();

        forge.put(FIRST, new Build(Build.SUCCESS, ""));
        after.round();
        assertThat(files.get("build-111111111111.txt")).as("the resumed watch still follows the commit")
                .contains("verdict: success");
    }

    @Test
    void endsWhenTheTaskStops() throws Exception {
        final int[] asked = {0};
        watch().run(() -> asked[0]++ < 3);

        assertThat(asked[0]).as("asked before each round, and the fourth answer ends it").isEqualTo(4);
    }

    private void later(final Duration duration) {
        now = now.plus(duration);
    }

    private Clock clock() {
        return new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(final java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
    }

    private static boolean contains(final Path file, final String marker) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(marker);
        } catch (IOException ex) {
            return false;
        }
    }
}
