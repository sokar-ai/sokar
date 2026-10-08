package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WaitingFetch}: one line a person pastes into their own clone, which reads and runs nothing.
 */
class WaitingFetchTest {

    @Test
    void bringsTheWaitingRefIntoARemoteBranchOfThePersonsOwnClone() {

        final String line = WaitingFetch.of(Path.of("/home/work/.local/share/sokar/mirrors/acme/backend.git"),
                "fix-login");

        assertThat(line).startsWith("git fetch ssh://" + System.getProperty("user.name") + "@")
                .contains("/home/work/.local/share/sokar/mirrors/acme/backend.git ")
                .endsWith(" refs/sokar/incoming/fix-login:refs/remotes/sokar/fix-login");
    }

    @Test
    void theAdviceSaysToReadItAndNotToRunIt() {

        assertThat(WaitingFetch.ADVICE).contains("safe mode").contains("Do not build it");
    }
}
