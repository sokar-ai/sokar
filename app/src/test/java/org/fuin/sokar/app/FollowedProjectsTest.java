package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link FollowedProjects}.
 */
class FollowedProjectsTest {

    @Test
    void an_account_that_follows_nothing_says_so(@TempDir final Path dir) throws IOException {
        assertThat(new FollowedProjects(dir.resolve("never-made")).all()).isEmpty();
    }

    @Test
    void remembers_what_a_person_told_it_to_follow(@TempDir final Path dir) throws IOException {
        final FollowedProjects projects = new FollowedProjects(dir);

        projects.follow("demo", "git@example.org:demo.git");

        assertThat(new FollowedProjects(dir).all()).singleElement().satisfies(one -> {
            assertThat(one.name()).isEqualTo("demo");
            assertThat(one.url()).isEqualTo("git@example.org:demo.git");
            assertThat(one.commit()).as("nothing has been verified yet").isEmpty();
        });
    }

    /** Following the same thing twice is somebody making sure, not a second entry. */
    @Test
    void following_the_same_project_again_is_not_an_error(@TempDir final Path dir)
            throws IOException {
        final FollowedProjects projects = new FollowedProjects(dir);
        projects.follow("demo", "git@example.org:demo.git");

        projects.follow("demo", "git@example.org:demo.git");

        assertThat(projects.all()).hasSize(1);
    }

    /**
     * The same name at a different address is a different project wearing a name, and repointing it
     * silently would change what a machine runs without anybody deciding to.
     */
    @Test
    void refuses_to_repoint_a_name_at_another_repository(@TempDir final Path dir)
            throws IOException {
        final FollowedProjects projects = new FollowedProjects(dir);
        projects.follow("demo", "git@example.org:demo.git");

        assertThatThrownBy(() -> projects.follow("demo", "git@elsewhere.org:demo.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stop following it first");
    }

    @Test
    void refuses_a_name_a_project_could_not_have(@TempDir final Path dir) {
        final FollowedProjects projects = new FollowedProjects(dir);

        assertThatThrownBy(() -> projects.follow("Not A Name", "git@example.org:x.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.follow("demo", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stops_following_what_it_was_told_to_stop(@TempDir final Path dir) throws IOException {
        final FollowedProjects projects = new FollowedProjects(dir);
        projects.follow("demo", "git@example.org:demo.git");

        assertThat(projects.unfollow("demo")).isTrue();
        assertThat(projects.all()).isEmpty();
        assertThat(projects.unfollow("demo")).as("and saying so twice is not an error").isFalse();
    }

    /** One unreadable record must not stop an account following everything else. */
    @Test
    void a_file_it_did_not_write_is_ignored(@TempDir final Path dir) throws IOException {
        final FollowedProjects projects = new FollowedProjects(dir);
        projects.follow("demo", "git@example.org:demo.git");
        Files.writeString(dir.resolve("rubbish.json"), "not json at all");

        assertThat(projects.all()).extracting(FollowedProjects.Followed::name)
                .containsExactly("demo");
    }
}
