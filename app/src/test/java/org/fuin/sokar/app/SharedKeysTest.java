package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.vault.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link SharedKeys}.
 * <p>
 * The owner check itself needs two real Unix users and belongs on a machine, not here. What these
 * hold is everything around it: that a directory nobody made is not an error, that the parser is
 * the same one the single-file keyring uses, and that a file whose name does not match its owner
 * counts as nothing rather than as a key.
 */
class SharedKeysTest {

    private String line(final String principal) {
        return principal + " " + SigningKey.generate(principal).authorizedKeysLine();
    }

    @Test
    void a_machine_where_this_was_never_allowed_has_no_keys(@TempDir final Path dir)
            throws IOException {
        assertThat(SharedKeys.read(dir.resolve("never-made"))).isEmpty();
    }

    @Test
    void reads_a_key_whose_directory_and_file_belong_to_its_account(@TempDir final Path dir)
            throws IOException {
        // The test runs as one user, so everything it creates is owned by that user - which makes
        // exactly one account name believable here, and it is the name to use.
        final String me = System.getProperty("user.name");
        Files.createDirectory(dir.resolve(me));
        Files.writeString(dir.resolve(me).resolve(SharedKeys.FILE), line(me) + "\n");

        assertThat(SharedKeys.read(dir)).extracting(MessageDelivery.Peer::name).containsExactly(me);
    }

    /**
     * The case the ownership check exists for: a directory and a key put there in another's name.
     */
    @Test
    void a_directory_named_after_another_account_counts_as_nothing(@TempDir final Path dir)
            throws IOException {
        Files.createDirectory(dir.resolve("somebody-else"));
        Files.writeString(dir.resolve("somebody-else").resolve(SharedKeys.FILE),
                line("somebody-else") + "\n");

        assertThat(SharedKeys.read(dir)).as("created by this user, named after another").isEmpty();
    }

    @Test
    void ignores_what_is_not_a_published_key(@TempDir final Path dir) throws IOException {
        final String me = System.getProperty("user.name");
        Files.writeString(dir.resolve(me + ".pub"), line(me) + "\n");
        Files.createDirectory(dir.resolve("empty-account"));

        assertThat(SharedKeys.read(dir)).as("a loose file is not an account, and an account with"
                + " no key is not a key").isEmpty();
    }

    @Test
    void owned_by_says_no_for_a_file_that_is_not_there(@TempDir final Path dir) {
        assertThat(SharedKeys.ownedBy(dir.resolve("nothing"), "anybody")).isFalse();
    }
}
