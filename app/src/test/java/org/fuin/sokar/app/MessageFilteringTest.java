package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link MessageFiltering}.
 */
class MessageFilteringTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Mailbox mailbox(final Path dir) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("sokar-p-t"));
        mailbox.create();
        return mailbox;
    }

    private Path filter(final Path dir) throws IOException {
        final Path file = dir.resolve(SokarPaths.MESSAGE_FILTER);
        Files.writeString(file, "#!/bin/sh\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    @Test
    void hands_the_mailbox_to_the_filter_and_never_asks_it_to_block(@TempDir final Path dir)
            throws IOException {
        final Mailbox mailbox = mailbox(dir);
        runner.answering(SokarPaths.MESSAGE_FILTER, "");

        final MessageFiltering.Outcome outcome =
                new MessageFiltering(runner, filter(dir)).run(mailbox);

        assertThat(outcome.ran()).isTrue();
        assertThat(runner.lines()).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("--mail " + mailbox.root())
                .as("a mailbox nobody has calibrated reports rather than refuses")
                .doesNotContain("--blocking");
    }

    @Test
    void a_refusal_is_an_ordinary_outcome(@TempDir final Path dir) throws IOException {
        runner.failing(SokarPaths.MESSAGE_FILTER, MessageFiltering.SOMETHING_REFUSED, "");

        final MessageFiltering.Outcome outcome =
                new MessageFiltering(runner, filter(dir)).run(mailbox(dir));

        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.exitCode()).isEqualTo(1);
        assertThat(outcome.detail()).isEmpty();
    }

    @Test
    void a_filter_that_cannot_start_is_reported_rather_than_swallowed(@TempDir final Path dir)
            throws IOException {
        runner.failing(SokarPaths.MESSAGE_FILTER, 2, "unknown key 'entropyThreshhold'");

        final MessageFiltering.Outcome outcome =
                new MessageFiltering(runner, filter(dir)).run(mailbox(dir));

        assertThat(outcome.ran()).isTrue();
        assertThat(outcome.detail()).contains("stopped with 2").contains("entropyThreshhold");
    }

    /**
     * The property the whole step exists for: with no filter, nothing is checked, so nothing may
     * leave. A machine that sent messages because the filter was missing would be the one failure
     * this design cannot afford.
     */
    @Test
    void a_machine_without_a_filter_sends_nothing(@TempDir final Path dir) throws IOException {
        final MessageFiltering.Outcome outcome = new MessageFiltering(runner, null)
                .run(mailbox(dir));

        assertThat(outcome.ran()).isFalse();
        assertThat(outcome.detail()).contains("nothing is sent");
        assertThat(runner.invocations()).isEmpty();
    }
}
