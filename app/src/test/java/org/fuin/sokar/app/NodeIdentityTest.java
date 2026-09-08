package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for what a node calls itself.
 * <p>
 * The only property that matters is that the answer is the <em>same</em> one every time and for
 * everybody. An identity that changed would be worse than none: a client would stop recognising a
 * node it already knew, and would show one machine as two.
 */
class NodeIdentityTest {

    @Test
    void answersTheSameThingEveryTime(@TempDir Path dir) {

        final Path file = dir.resolve("data").resolve("node-id");

        final String first = NodeIdentity.of(file);
        final String second = NodeIdentity.of(file);

        assertThat(first).isNotBlank().isEqualTo(second);
    }

    @Test
    void survivesARestartBecauseItIsOnDisk(@TempDir Path dir) throws Exception {

        // The whole point. Minting a fresh one at every start would make a node unrecognisable
        // after a reboot, which is the failure this is meant to prevent, arriving by another door.
        final Path file = dir.resolve("node-id");
        final String minted = NodeIdentity.of(file);

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains(minted);
        assertThat(NodeIdentity.of(file)).isEqualTo(minted);
    }

    @Test
    void twoDaemonsStartingAtOnceMintOneIdentityBetweenThem(@TempDir Path dir) throws Exception {

        // Read-then-write would let a second process arrive in the middle and win, leaving two
        // processes answering differently for the same node. CREATE_NEW has no middle: the loser
        // reads what the winner wrote.
        final Path file = dir.resolve("node-id");
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final var tasks = IntStream.range(0, 8)
                    .<Callable<String>>mapToObj(i -> () -> NodeIdentity.of(file)).toList();
            final Set<String> answers = pool.invokeAll(tasks).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }).collect(Collectors.toSet());

            assertThat(answers).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoNodesUnderDifferentUsersAreDifferentIdentities(@TempDir Path dir) {

        // A node is an OS user with a daemon, not a machine, and the file lives under the data
        // directory - which is already per user. Deriving this from the machine instead would
        // give two accounts on one host the same answer, which is precisely the case a hostname
        // already gets wrong.
        assertThat(NodeIdentity.of(dir.resolve("alice").resolve("node-id")))
                .isNotEqualTo(NodeIdentity.of(dir.resolve("bob").resolve("node-id")));
    }

    @Test
    void anEmptyFileIsReplacedRatherThanReadAsAnIdentity(@TempDir Path dir) throws Exception {

        // A run interrupted between creating the file and writing it leaves nothing in it. Read
        // back as an identity, two nodes would both answer "" and a client would call them the
        // same node - the exact failure this exists to prevent.
        final Path file = dir.resolve("node-id");
        Files.createDirectories(dir);
        Files.writeString(file, "   \n");

        final String repaired = NodeIdentity.of(file);

        assertThat(repaired).isNotBlank();
        assertThat(NodeIdentity.of(file)).isEqualTo(repaired);
    }

    @Test
    void isReadableOnlyByItsOwner(@TempDir Path dir) throws Exception {

        final Path file = dir.resolve("node-id");
        NodeIdentity.of(file);

        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrderElementsOf(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
}
