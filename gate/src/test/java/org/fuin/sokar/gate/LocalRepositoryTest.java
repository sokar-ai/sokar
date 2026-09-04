package org.fuin.sokar.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.sokar.core.process.FakeCommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link LocalRepository}.
 */
class LocalRepositoryTest {

    @Test
    void returnsTheWorkTreeRoot() {
        assertThat(LocalRepository.topLevel(
                new FakeCommandRunner().answering("show-toplevel", "/home/you/project\n"),
                Path.of(".")))
                .isEqualTo(Path.of("/home/you/project"));
    }

    @Test
    void answersNothingOutsideARepository() {

        // The negative case: treating a failure as a path would seed the gate from whatever
        // string git happened to print on stderr.
        assertThat(LocalRepository.topLevel(
                new FakeCommandRunner().failing("show-toplevel", 128, "not a git repository"),
                Path.of("."))).isNull();
        assertThat(LocalRepository.topLevel(
                new FakeCommandRunner().answering("show-toplevel", "  \n"), Path.of("."))).isNull();
    }
}
