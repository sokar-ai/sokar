package org.fuin.sokar.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GitSubprocess}: a request's git is bounded in time.
 */
class GitSubprocessTest {

    @Test
    void aGitThatHangsIsStoppedAtItsLimit() {

        // The limit was waited for only after all output had been read, so it started once git had closed its
        // output: a hung git held its request for ever.
        final Instant started = Instant.now();

        assertThatThrownBy(() -> new GitSubprocess("sleep", 1).run(List.of("30"), new byte[0]))
                .isInstanceOf(IOException.class).hasMessageContaining("timed out");
        assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aGitThatAnswersInTimeIsAnswered() throws IOException {
        assertThat(new String(new GitSubprocess("echo", 5).run(List.of("hello"), new byte[0])).strip())
                .isEqualTo("hello");
    }
}
