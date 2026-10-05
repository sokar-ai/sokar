package org.fuin.sokar.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link GrantedNames}, the record of what somebody let a running task reach.
 */
class GrantedNamesTest {

    @Test
    void remembersWhatWasGranted(@TempDir Path dir) throws IOException {

        GrantedNames.add(dir, "docs.example.test");

        assertThat(GrantedNames.all(dir)).containsExactly("docs.example.test");
        assertThat(GrantedNames.covers(dir, "docs.example.test")).isTrue();
    }

    @Test
    void aGrantCoversWhatIsUnderIt(@TempDir Path dir) throws IOException {

        // Because that is what the resolver does with it: dnsmasq matches server=/example.test/
        // for cdn.example.test too. A stricter rule here would leave a name resolving and then
        // blocked, which is the one combination the design exists to avoid.
        GrantedNames.add(dir, "example.test");

        assertThat(GrantedNames.covers(dir, "cdn.example.test")).isTrue();
        assertThat(GrantedNames.covers(dir, "a.b.example.test")).isTrue();
    }

    @Test
    void aGrantDoesNotCoverANameThatMerelyEndsSimilarly(@TempDir Path dir) throws IOException {

        // 'notexample.test' is not under 'example.test', however much the strings look alike.
        GrantedNames.add(dir, "example.test");

        assertThat(GrantedNames.covers(dir, "notexample.test")).isFalse();
    }

    @Test
    void coversNothingWhenNothingWasGranted(@TempDir Path dir) {

        // The ordinary case for every task, and the safe direction: the watcher goes on asking.
        assertThat(GrantedNames.all(dir)).isEmpty();
        assertThat(GrantedNames.covers(dir, "example.test")).isFalse();
    }

    @Test
    void twoGrantsAtOnceCannotLoseEachOther(@TempDir Path dir) throws Exception {

        // Appended, never rewritten: a read-modify-write file would drop one of these, and the
        // grant that went missing would look like a grant that was ignored.
        final int writers = 16;
        final java.util.concurrent.CountDownLatch go =
                new java.util.concurrent.CountDownLatch(1);
        final java.util.List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < writers; i++) {
            final String name = "host-" + i + ".example.test";
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    go.await();
                    GrantedNames.add(dir, name);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            }));
        }
        go.countDown();
        for (final Thread thread : threads) {
            thread.join();
        }

        assertThat(GrantedNames.all(dir)).hasSize(writers);
    }

    @Test
    void grantingTheSameNameTwiceIsStillOneName(@TempDir Path dir) throws IOException {

        // Two people can decide the same thing, and the list is what a watcher reads.
        GrantedNames.add(dir, "example.test");
        GrantedNames.add(dir, "example.test");

        assertThat(GrantedNames.all(dir)).containsExactly("example.test");
    }

    @Test
    void ignoresBlankLinesInAFileSomebodyEdited(@TempDir Path dir) throws IOException {

        Files.writeString(dir.resolve(GrantedNames.FILE), "\n  \nexample.test\n\n");

        assertThat(GrantedNames.all(dir)).containsExactly("example.test");
    }
}
