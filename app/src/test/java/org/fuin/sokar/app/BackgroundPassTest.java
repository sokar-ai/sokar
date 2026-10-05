package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link BackgroundPass}: the daemon's long-lived passes never take the carriers its socket's calls need.
 */
class BackgroundPassTest {

    @Test
    void aPassRunsOnAPlatformDaemonThreadOfItsOwn() throws Exception {
        final Thread[] seen = new Thread[1];
        final Thread started = BackgroundPass.start("sokar-test-pass", () -> seen[0] = Thread.currentThread());
        started.join(5_000);

        assertThat(seen[0]).isNotNull();
        assertThat(seen[0].isVirtual()).isFalse();
        assertThat(seen[0].isDaemon()).isTrue();
        assertThat(seen[0].getName()).isEqualTo("sokar-test-pass");
    }

    @Test
    void noneOfTheDaemonsPassesStartsOnAVirtualThread() throws Exception {

        // Four passes, each started where it lives. A virtual one pinned in a native call took a carrier from the
        // socket's calls; two of them on a two-CPU machine took both, and the daemon answered nothing.
        for (final String pass : List.of("ConfigurationWatch", "UpstreamWatch", "MessageWatch")) {
            // In whichever area module holds it: the package is one, its sources are in several.
            final Path file;
            try (var modules = Files.list(Path.of(".."))) {
                file = modules.filter(module -> module.getFileName().toString().matches("app(-[a-z]+)?"))
                        .map(module -> module.resolve("src/main/java/org/fuin/sokar/app/" + pass + ".java"))
                        .filter(Files::exists).findFirst().orElseThrow();
            }
            final String source = Files.readString(file);
            assertThat(source).as(pass).contains("BackgroundPass.start(").doesNotContain("Thread.ofVirtual()");
        }
    }
}
