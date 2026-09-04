package org.fuin.sokar.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Limits}.
 */
class LimitsTest {

    @Test
    void capsMemoryAndProcessesByDefault() {

        // A project that says nothing must still be limited: the default is the whole protection
        // for everyone who never reads this part of the guide.
        assertThat(Limits.defaults().memory()).isEqualTo("8g");
        assertThat(Limits.defaults().pids()).isEqualTo(2048);
    }

    @Test
    void leavesCpuAloneByDefault() {

        // Starving a task of CPU only makes it slow, and a wrong cap here is a support question
        // rather than a protection.
        assertThat(Limits.defaults().cpus()).isNull();
    }

    @Test
    void refusesAProcessLimitThatLeavesNothingToRun() {
        assertThatThrownBy(() -> new Limits("8g", null, 0))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("leaves nothing to run");
    }
}
