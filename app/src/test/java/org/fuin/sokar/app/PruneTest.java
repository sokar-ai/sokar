package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Prune}, against real directories: what nothing owns is found, shown, and removed only when asked,
 * and what holds work stays unless that is asked too.
 */
class PruneTest {

    @TempDir
    Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A project as September left it: a registry entry into a deleted temporary directory, and its mirror. */
    private Path leftOver(SokarContext context, String project) throws IOException {
        final Path registry = Files.createDirectories(context.paths().projects().projectRegistry()).resolve(project);
        Files.writeString(registry, dir.resolve("tmp.gone/project.yml") + "\n");
        final Path mirror = Files.createDirectories(context.paths().xdg().data().resolve("mirrors")
                .resolve(project + ".git"));
        Files.createDirectories(context.paths().tasks().buildContext(project));
        return mirror;
    }

    @Test
    void aProjectWhoseFileIsGoneIsShownAndRemovedOnlyWithYes() throws IOException {
        final SokarContext context = context();
        final Path mirror = leftOver(context, "gatedemo");

        final Prune.Result shown = new Prune(context).run(false, false);

        assertThat(shown.removes()).extracting(Prune.Item::kind, Prune.Item::name)
                .contains(org.assertj.core.groups.Tuple.tuple("PROJECT", "gatedemo"))
                .as("its build context is the project's, not listed a second time on its own")
                .doesNotContain(org.assertj.core.groups.Tuple.tuple("BUILD",
                        context.paths().tasks().buildContext("gatedemo").toString()));
        assertThat(mirror).as("nothing removed without --yes").exists();

        final Prune.Result done = new Prune(context).run(true, false);

        assertThat(done.applied()).isTrue();
        assertThat(mirror).doesNotExist();
        assertThat(context.paths().projects().projectRegistry().resolve("gatedemo")).doesNotExist();
        assertThat(context.paths().tasks().buildContext("gatedemo")).doesNotExist();
        assertThat(new Prune(context).run(false, false).found()).as("nothing left to find").isFalse();
    }

    @Test
    void aMirrorWithAPushNobodyReviewedIsKeptUntilWorkIsIncluded() throws IOException {
        final SokarContext context = context();
        final Path mirror = leftOver(context, "gatetask");
        runner.answering("for-each-ref", "refs/sokar/incoming/shell-1\n");

        final Prune.Result kept = new Prune(context).run(true, false);

        assertThat(kept.keeps()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("gatetask");
            assertThat(item.holds()).contains("pushes nobody reviewed").contains("shell-1");
        });
        assertThat(mirror).exists();

        new Prune(context).run(true, true);

        assertThat(mirror).as("--including-work").doesNotExist();
    }

    @Test
    void aProjectWithARunningTaskOrAReadableFileIsNeverOrphaned() throws IOException {
        final SokarContext context = context();
        leftOver(context, "busy");
        runner.answering("ps --all", "sokar-busy-shell\tUp 2 minutes\t\t\tbusy\tguarded\t\t\n");
        final Path file = Files.createDirectories(dir.resolve("readable")).resolve("project.yml");
        Files.writeString(file, "project:\n  name: \"kept\"\n  security_class: \"guarded\"\n"
                + "image:\n  base_image: \"ubuntu:24.04\"\n");
        Files.writeString(context.paths().projects().projectRegistry().resolve("kept"), file + "\n");

        final Prune.Result found = new Prune(context).run(false, false);

        assertThat(java.util.stream.Stream.concat(found.removes().stream(), found.keeps().stream())
                .map(Prune.Item::name)).doesNotContain("busy", "kept");
    }

    @Test
    void whatIsKeptForATaskWhoseContainerIsGoneGoesUnlessItsMailboxHoldsAMessage() throws IOException {
        final SokarContext context = context();
        Files.createDirectories(context.paths().tasks().taskRecord("sokar-old-shell-1"));
        new Mailbox(context.paths().messaging().mailbox("sokar-old-shell-1")).create();
        final Mailbox waiting = new Mailbox(context.paths().messaging().mailbox("sokar-old-shell-2"));
        waiting.create();
        Files.writeString(waiting.hold().resolve("m-1.json"), "{\"messageId\":\"m-1\"}");
        age(context.paths().xdg().state());

        final Prune.Result result = new Prune(context).run(true, false);

        assertThat(result.removes()).as(result.toString()).extracting(Prune.Item::name).contains("sokar-old-shell-1");
        assertThat(context.paths().tasks().taskRecord("sokar-old-shell-1")).doesNotExist();
        assertThat(context.paths().messaging().mailbox("sokar-old-shell-1")).doesNotExist();
        assertThat(result.keeps()).singleElement().satisfies(item -> {
            assertThat(item.name()).isEqualTo("sokar-old-shell-2");
            assertThat(item.holds()).contains("held or refused");
        });
        assertThat(waiting.hold().resolve("m-1.json")).exists();
    }

    @Test
    void aTaskStartingNowIsNotLeftOverThoughItsContainerIsNotThereYet() throws IOException {
        // Its mailbox and state are made before its container: a prune at that moment must not take them.
        final SokarContext context = context();
        new Mailbox(context.paths().messaging().mailbox("sokar-p-starting")).create();

        assertThat(new Prune(context).run(true, false).found()).isFalse();
        assertThat(context.paths().messaging().mailbox("sokar-p-starting")).exists();
    }

    @Test
    void theLoginImageAndBuildContextAreNeverAProjectsLeftovers() throws IOException {
        final SokarContext context = context();
        Files.createDirectories(context.paths().tasks().buildContext(Prune.LOGIN));
        Files.createDirectories(context.paths().tasks().buildContext("gone"));
        runner.answering("images", "localhost/sokar/sokar-login\nlocalhost/sokar/gone\n");

        final Prune.Result found = new Prune(context).run(false, false);

        assertThat(found.removes()).extracting(Prune.Item::name)
                .contains("sokar/gone", context.paths().tasks().buildContext("gone").toString())
                .doesNotContain("sokar/sokar-login", context.paths().tasks().buildContext(Prune.LOGIN).toString());
    }

    @Test
    void aCloneLeftBesideNoFollowRecordIsALeftoverAndAFollowedOnesIsNot() throws IOException {
        final SokarContext context = context();
        Files.createDirectories(context.paths().projects().followedClone("refused"));
        new FollowedProjects(context.paths().projects().followed()).follow("kept", "git@example.org:kept.git", true);
        Files.createDirectories(context.paths().projects().followedClone("kept"));

        final Prune.Result found = new Prune(context).run(true, false);

        assertThat(context.paths().projects().followedClone("refused")).doesNotExist();
        assertThat(context.paths().projects().followedClone("kept")).exists();
        assertThat(found.removes()).extracting(Prune.Item::name)
                .contains(context.paths().projects().followedClone("refused").toString());
    }

    @Test
    void aVaultEntryIsOrphanedOnlyWhenTheTaskOrProjectItIsKeptForIsGone() {
        final Set<String> containers = Set.of("sokar-p-live");
        final Set<String> projects = Set.of("p");

        assertThat(Prune.orphanedEntry("task/sokar-p-gone/route/forge", containers, projects)).isTrue();
        assertThat(Prune.orphanedEntry("task/sokar-p-live/gate-token", containers, projects)).isFalse();
        assertThat(Prune.orphanedEntry("transport/matrix/task/sokar-p-gone", containers, projects)).isTrue();
        assertThat(Prune.orphanedEntry("transport/matrix/project/gone", containers, projects)).isTrue();
        assertThat(Prune.orphanedEntry("transport/matrix/project/p", containers, projects)).isFalse();
        assertThat(Prune.orphanedEntry("transport/matrix/account", containers, projects))
                .as("the account's, owned by no task or project").isFalse();
        assertThat(Prune.orphanedEntry("grant/forge-app", containers, projects)).isFalse();
    }

    /** Makes everything under a directory look untouched for longer than {@link Prune#SETTLED}. */
    private static void age(Path root) throws IOException {
        final java.nio.file.attribute.FileTime then = java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minus(Prune.SETTLED).minusSeconds(60));
        try (java.util.stream.Stream<Path> tree = Files.walk(root)) {
            for (final Path path : tree.toList()) {
                Files.setLastModifiedTime(path, then);
            }
        }
    }
}
