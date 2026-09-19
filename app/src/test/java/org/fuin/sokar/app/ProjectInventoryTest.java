package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProjectInventory}, which answers the question an interface cannot answer for
 * itself: what projects are there, and where is each one's file.
 */
class ProjectInventoryTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private SokarContext context(Path dir) {
        root = dir;
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private void mirror(String name) throws IOException {
        Files.createDirectories(root.resolve("data/sokar/mirrors").resolve(name + ".git"));
    }

    /** The mirror of a repository the project names, which sits one directory down. */
    private void mirror(String project, String repository) throws IOException {
        Files.createDirectories(root.resolve("data/sokar/mirrors").resolve(project)
                .resolve(repository + ".git"));
    }

    private void task(String container, String project) throws IOException {
        final Path state = root.resolve("run/sokar").resolve(container);
        Files.createDirectories(state);
        new Sidecar(Sidecar.VERSION, project, "guarded", state.resolve("r.nft").toString(),
                state.resolve("dns.conf").toString(), "/usr/bin/sokar", state.toString())
                .writeTo(state.resolve("sidecar.json"));
    }

    /** One file per project, which is how the registry a task start writes is shaped. */
    private void registry(Path dir, String name, Path projectFile) throws IOException {
        final Path entry = dir.resolve("data/sokar/projects").resolve(name);
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, projectFile + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void saysAProjectWithAnImageCanStartWorkWithoutBuildingOne(@TempDir Path dir)
            throws IOException {

        final SokarContext context = context(dir);
        mirror("uc");
        runner.answering("images", "sokar/uc\nsokar/other\nubuntu\n");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.prepared()).isTrue());
    }

    @Test
    void saysAProjectWithNoImageWillHaveToBuildOneFirst(@TempDir Path dir) throws IOException {

        // A normal state, not a fault: it means the first task here spends minutes building
        // before anything happens, which is worth showing before somebody presses start.
        final SokarContext context = context(dir);
        mirror("uc");
        runner.answering("images", "sokar/somethingelse\nubuntu\n");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.prepared()).isFalse());
    }

    @Test
    void doesNotMistakeAnImageWhoseNameMerelyStartsTheSame(@TempDir Path dir) throws IOException {

        // 'sokar/uc-staging' is a different project. Matching on a prefix would mark a project
        // ready that has never been built, and the first task would then spend minutes doing
        // what an interface said was already done.
        final SokarContext context = context(dir);
        mirror("uc");
        runner.answering("images", "sokar/uc-staging\n");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.prepared()).isFalse());
    }

    @Test
    void asksPodmanForImagesOnceHoweverManyProjectsThereAre(@TempDir Path dir) throws IOException {

        // The cost the interface cannot see. It re-reads this list after every task start and
        // every approval, so a subprocess per project would turn ordinary use into a stall
        // nothing on screen explains.
        final SokarContext context = context(dir);
        for (final String name : java.util.List.of("one", "two", "three", "four")) {
            mirror(name);
        }
        runner.answering("images", "sokar/one\n");

        assertThat(new ProjectInventory(context).projects()).hasSize(4);
        assertThat(runner.lines().stream().filter(line -> line.contains("image"))).hasSize(1);
    }

    @Test
    void reportsAProjectNothingHasMeasuredAsNeverCheckedRatherThanUpToDate(@TempDir Path dir)
            throws IOException {

        final SokarContext context = context(dir);
        mirror("uc");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.asMap()).containsEntry("behindReason", "NEVER_CHECKED");
                    assertThat(project.asMap()).containsEntry("behindMeasured", "");
                });
    }

    @Test
    void reportsWhatWasMeasuredWithTheMomentItWasTaken(@TempDir Path dir) throws IOException {

        // The age is not decoration. Without it a number has to be presented as though it were
        // current, and a stale answer that looks fresh is worse than a stale one.
        final SokarContext context = context(dir);
        mirror("uc");
        new UpstreamRecords(context.paths().upstreamRecords()).put("uc",
                new org.fuin.sokar.gate.UpstreamDistance.Distance(3,
                        java.time.Instant.parse("2026-09-07T17:44:30Z"),
                        org.fuin.sokar.gate.UpstreamDistance.Reason.MEASURED, null));

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.asMap())
                        .containsEntry("behind", 3)
                        .containsEntry("behindMeasured", "2026-09-07T17:44:30Z")
                        .containsEntry("behindReason", "MEASURED"));
    }

    @Test
    void nothingOnTheListingPathCanMeasureAnUpstream() {

        // Checked statically, not by watching a fake runner. The first version of this test
        // asserted that no "fetch" reached the injected runner - and a mutation that measured with
        // a runner of its own sailed straight past it, because the fake never saw the call. What
        // has to be true is that this code cannot reach the network AT ALL, whichever runner it
        // builds, and that is a question about which methods it may call.
        //
        // The whole reason the measuring lives on a timer: this list is re-read after every task
        // start and every approval, so a fetch hidden in it would turn ordinary use of the
        // interface into traffic nothing on screen accounts for.
        final com.tngtech.archunit.core.domain.JavaClasses classes =
                new com.tngtech.archunit.core.importer.ClassFileImporter()
                        .importPackages("org.fuin.sokar.app");

        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().haveSimpleName("ProjectInventory")
                .or().haveSimpleName("TaskInventory")
                .should().callMethodWhere(com.tngtech.archunit.base.DescribedPredicate
                        .describe("measures an upstream over the network",
                                target -> target.getTarget().getOwner().getName()
                                        .equals("org.fuin.sokar.gate.UpstreamDistance")))
                .because("listing projects must never reach the network; measuring happens on a"
                        + " timer in UpstreamWatch and is only read back here")
                .check(classes);
    }

    @Test
    void findsAProjectByItsMirrorAlone(@TempDir Path dir) throws IOException {

        // The mirror outlives every task, so it is the only durable list of names there is.
        final SokarContext context = context(dir);
        mirror("uc");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.name()).isEqualTo("uc");
                    assertThat(project.mirror()).endsWith("uc.git");
                    assertThat(project.file()).isNull();
                });
    }

    @Test
    void findsAProjectThatHasOnlyATask(@TempDir Path dir) throws IOException {

        // A task that ran without a gate leaves no mirror, and its project still exists.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-nogate-shell-1\tUp 4 minutes\n");
        task("sokar-nogate-shell-1", "nogate");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.name()).isEqualTo("nogate");
                    assertThat(project.securityClass()).isEqualTo("guarded");
                    assertThat(project.tasks()).isEqualTo(1);
                    assertThat(project.mirror()).isNull();
                });
    }

    @Test
    void namesWhereEachProjectFileIs(@TempDir Path dir) throws IOException {

        // The one thing a client on another machine cannot work out, and what every gate method
        // takes.
        final SokarContext context = context(dir);
        mirror("uc");
        final Path projectFile = Files.writeString(dir.resolve("project.yml"), "project:\n");
        registry(dir, "uc", projectFile);

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.file()).isEqualTo(projectFile.toString()));
    }

    @Test
    void reportsAFileThatHasMovedAsAbsent(@TempDir Path dir) throws IOException {

        // Handing back a path nothing can read makes the next gate call fail for a reason that
        // looks like a fault in the daemon.
        final SokarContext context = context(dir);
        mirror("uc");
        registry(dir, "uc", dir.resolve("moved-away.yml"));

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.file()).isNull());
    }

    @Test
    void countsWhatIsWaitingForReview(@TempDir Path dir) throws IOException {

        // Asked of git, not counted as files: a packed mirror keeps its refs in one file, and a
        // loose-file count would report a full queue as empty.
        final SokarContext context = context(dir);
        mirror("uc");
        runner.answering("for-each-ref",
                "refs/sokar/incoming/shell\nrefs/sokar/incoming/build\n");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.pending()).isEqualTo(2));
    }

    @Test
    void countsWhatIsWaitingInEveryRepositoryOfTheProject(@TempDir Path dir) throws IOException {

        // A project is a unit of work over one or more repositories, and each keeps its own
        // mirror. Counting only the project's own would report a number that silently excludes
        // repositories - worse than reporting none, because a number that is there is read as the
        // answer. The fake answers the same two refs for every mirror it is asked about, so three
        // mirrors is six: what this measures is that all three were asked.
        final SokarContext context = context(dir);
        mirror("uc");
        mirror("uc", "backend");
        mirror("uc", "frontend");
        runner.answering("for-each-ref",
                "refs/sokar/incoming/shell\nrefs/sokar/incoming/build\n");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> assertThat(project.pending()).isEqualTo(6));
    }

    @Test
    void keepsAProjectWhoseTaskAndMirrorAreBothGone(@TempDir Path dir) throws IOException {

        // It ran once and everything was removed. The file is still what an interface acts on.
        final SokarContext context = context(dir);
        final Path projectFile = Files.writeString(dir.resolve("project.yml"), "project:\n");
        registry(dir, "ghost", projectFile);

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.name()).isEqualTo("ghost");
                    assertThat(project.tasks()).isZero();
                    assertThat(project.file()).isEqualTo(projectFile.toString());
                });
    }

    @Test
    void countsEveryTaskOfAProjectAndSaysHowManyAreUp(@TempDir Path dir) throws IOException {

        // Both numbers: a stopped task keeps its workspace and can be resumed, so it is not the
        // same as no task at all, and an interface showing only running ones would hide it.
        final SokarContext context = context(dir);
        runner.answering("ps",
                "sokar-uc-shell-1\tUp 4 minutes\nsokar-uc-build-2\tExited (0) 1 hour ago\n");
        task("sokar-uc-shell-1", "uc");
        task("sokar-uc-build-2", "uc");
        mirror("uc");

        assertThat(new ProjectInventory(context).projects()).singleElement()
                .satisfies(project -> {
                    assertThat(project.tasks()).isEqualTo(2);
                    assertThat(project.running()).isEqualTo(1);
                    assertThat(project.mirror()).endsWith("uc.git");
                });
    }

    @Test
    void listsAProjectWithNothingRunning(@TempDir Path dir) throws IOException {

        // The ordinary case. A list of only active projects would be empty on a machine with a
        // dozen projects on it.
        final SokarContext context = context(dir);
        runner.answering("ps", "sokar-uc-shell-1\tExited (0) 1 hour ago\n");
        task("sokar-uc-shell-1", "uc");
        mirror("idle");

        assertThat(new ProjectInventory(context).projects()).hasSize(2);
        assertThat(new ProjectInventory(context).projects())
                .allSatisfy(project -> assertThat(project.running()).isZero());
    }

    @Test
    void sayingWhatEachRepositoryReachesAndMayUse(@TempDir Path dir) throws IOException {

        final SokarContext context = context(dir);
        mirror("uc");
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                limits:
                  memory: "16g"
                  pids: 4096
                egress:
                  sets: [maven]
                repositories:
                  frontend:
                    egress:
                      sets: [flutter]
                      domains: ["pub.dev"]
                    limits:
                      memory: "32g"
                """);
        registry(dir, "uc", file);

        final var repositories = new ProjectInventory(context).projects().get(0).repositories();
        final var frontend = repositories.stream()
                .filter(one -> one.name().equals("frontend")).findFirst().orElseThrow();

        // One list with a source on each entry: a repository's grants are ADDED, so a screen
        // showing both halves should not have to subtract one list from another.
        assertThat(frontend.egress()).extracting(ProjectInventory.Grant::value,
                        ProjectInventory.Grant::from)
                .containsExactly(org.assertj.core.api.Assertions.tuple("maven", "project"),
                        org.assertj.core.api.Assertions.tuple("flutter", "repository"),
                        org.assertj.core.api.Assertions.tuple("pub.dev", "repository"));

        // A limit REPLACES key by key, so what matters is which key it replaced.
        assertThat(frontend.limits().memory()).isEqualTo("32g");
        assertThat(frontend.limits().memoryFrom()).isEqualTo("repository");
        // And the keys it said nothing about are the project's - not the defaults. The project
        // raised both away from 8g and 2048 so that a fallback would fail this.
        assertThat(frontend.limits().pids()).isEqualTo(4096);
        assertThat(frontend.limits().pidsFrom()).isEqualTo("project");

        // The project's own repository adds nothing, and says so.
        final var own = repositories.stream()
                .filter(ProjectInventory.RepositorySummary::own).findFirst().orElseThrow();
        assertThat(own.egress()).extracting(ProjectInventory.Grant::from)
                .containsOnly("project");
        assertThat(own.limits().memory()).isEqualTo("16g");
    }
}
