package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where one project's file comes from, when a machine follows its repository.
 * <p>
 * <strong>This is the question that had three answers.</strong> The directory somebody stood in,
 * the one a create call chose, and the clone the machine verified - and until this existed, the
 * verified one was the only one nothing read. Everything following promises rests on the answer
 * being the checked file.
 */
class ProjectSourceTest {

    private SokarContext context(final Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A project this machine follows, with a file in its clone. */
    private void followed(final SokarContext context, final String name, final String commit,
            final String content) throws IOException {
        new FollowedProjects(context.paths().followed()).write(
                new FollowedProjects.Followed(name, "git@example.com:x/" + name + ".git",
                        commit, "2026-09-19T00:00:00Z", "APPLIED", ""));
        final Path clone = context.paths().followedClone(name);
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("project.yml"), content);
    }

    /** A project a task once ran for, recorded where its file was. */
    private Path recorded(final SokarContext context, final Path dir, final String name)
            throws IOException {
        final Path file = dir.resolve(name + "-local.yml");
        Files.writeString(file, "project:\n  name: \"" + name + "\"\n");
        new ProjectRegistry(context.paths().projectRegistry()).remember(name, file);
        return file;
    }

    @Test
    void theVerifiedCloneWinsOverAFileSomebodyRecorded(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        recorded(context, dir, "acme");
        followed(context, "acme", "a1b2c3", "project:\n  name: \"acme\"\n");

        final ProjectSource.Found found = ProjectSource.resolve(context, "acme");

        // Not close: one was checked against a key pinned out of band, the other was wherever a
        // task last ran.
        assertThat(found.outcome()).isEqualTo(ProjectSource.Outcome.FOLLOWED);
        assertThat(found.file()).isEqualTo(context.paths().followedClone("acme")
                .resolve("project.yml"));
        assertThat(found.commit()).isEqualTo("a1b2c3");
        assertThat(found.verified()).isTrue();
    }

    @Test
    void aCloneFollowedWithoutAnAnchorIsNotCalledVerified(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        new FollowedProjects(context.paths().followed()).write(
                new FollowedProjects.Followed("acme", "/srv/acme", "a1b2c3", "2026-09-19T00:00:00Z", "APPLIED",
                        "", "", "", true));
        Files.createDirectories(context.paths().followedClone("acme"));
        Files.writeString(context.paths().followedClone("acme").resolve("project.yml"), "project:\n  name: \"acme\"\n");

        final ProjectSource.Found found = ProjectSource.resolve(context, "acme");

        // Applied, and nobody checked a signature on it: a task start said "verified" about exactly this.
        assertThat(found.commit()).isEqualTo("a1b2c3");
        assertThat(found.unverified()).isTrue();
        assertThat(found.verified()).isFalse();
    }

    @Test
    void aProjectNobodyFollowsStillResolvesToWhereItWasRecorded(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path file = recorded(context, dir, "solo");

        final ProjectSource.Found found = ProjectSource.resolve(context, "solo");

        assertThat(found.outcome()).isEqualTo(ProjectSource.Outcome.RECORDED);
        assertThat(found.file()).isEqualTo(file);
        // Nothing checked it, and the answer says so rather than implying otherwise.
        assertThat(found.verified()).isFalse();
    }

    @Test
    void aFollowedProjectWithNothingInForceResolvesToNothing(@TempDir final Path dir)
            throws IOException {
        final SokarContext context = context(dir);
        final Path local = recorded(context, dir, "acme");
        // Followed, and refused: no clone was ever written.
        new FollowedProjects(context.paths().followed()).write(
                new FollowedProjects.Followed("acme", "git@example.com:x/acme.git",
                        "", "2026-09-19T00:00:00Z", "UNKNOWN_KEY", "a key that is not pinned"));

        final ProjectSource.Found found = ProjectSource.resolve(context, "acme");

        // The local file is NOT the fallback. Falling back would run exactly the thing the machine
        // declined to apply, which is worse than not starting - and it would make a refusal look
        // like it had no effect.
        assertThat(found.outcome()).isEqualTo(ProjectSource.Outcome.FOLLOWED);
        assertThat(found.file()).isNull();
        assertThat(found.verified()).isFalse();
        assertThat(local).exists();
    }

    @Test
    void aNameThisMachineDoesNotHaveIsItsOwnAnswer(@TempDir final Path dir) {
        assertThat(ProjectSource.resolve(context(dir), "nowhere").outcome())
                .isEqualTo(ProjectSource.Outcome.UNKNOWN);
    }

    @Test
    void theNamesItCanResolveAreFollowedFirst(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        recorded(context, dir, "solo");
        followed(context, "acme", "a1b2c3", "project:\n  name: \"acme\"\n");

        // What a refusal prints. Followed first, because that is the list somebody should be
        // choosing from.
        assertThat(ProjectSource.names(context)).containsExactly("acme", "solo");
    }

    @Test
    void aRecordedFileThatIsGoneIsNotAnAnswer(@TempDir final Path dir) throws IOException {
        final SokarContext context = context(dir);
        Files.delete(recorded(context, dir, "solo"));

        // The registry remembers where a task last read one; the file can be deleted afterwards
        // and nothing here would know. Answering with a path that is not there would make the
        // failure happen somewhere further in.
        assertThat(ProjectSource.resolve(context, "solo").outcome())
                .isEqualTo(ProjectSource.Outcome.UNKNOWN);
    }

    @Test
    void whetherAProjectIsStuckIsDerivedFromItsOutcome(@TempDir final Path dir) {
        // Derived, never stored: two fields that must agree are two fields that can disagree, and
        // a stored one would rot the first time an outcome was added.
        assertThat(new FollowedProjects.Followed("a", "u", "", "", "UNKNOWN_KEY", "")
                .needsAPerson()).isTrue();
        assertThat(new FollowedProjects.Followed("a", "u", "", "", "VAULT_LOCKED", "")
                .needsAPerson()).isTrue();
        // An unreachable repository may answer on the next pass by itself, so it is NOT stuck -
        // which is the distinction an interface cannot make for itself.
        assertThat(new FollowedProjects.Followed("a", "u", "", "", "UNREACHABLE", "")
                .needsAPerson()).isFalse();
        assertThat(new FollowedProjects.Followed("a", "u", "", "", "APPLIED", "")
                .needsAPerson()).isFalse();
        // Never tried is not stuck either.
        assertThat(new FollowedProjects.Followed("a", "u", "", "", "", "")
                .needsAPerson()).isFalse();
    }
}
