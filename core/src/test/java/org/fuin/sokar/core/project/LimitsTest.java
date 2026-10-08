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
        assertThat(Limits.bytes("512", "k")).isEqualTo(512L);
        assertThat(Limits.bytes("64k", "k")).isEqualTo(64L * 1024);
        assertThat(Limits.bytes("64M", "k")).isEqualTo(64L * 1024 * 1024);
        assertThat(Limits.bytes("2g", "k")).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThatThrownBy(() -> Limits.bytes("64 MB", "limits.hand_in")).hasMessageContaining("limits.hand_in");
        assertThatThrownBy(() -> Limits.bytes("99999999999999999999g", "k")).hasMessageContaining("too large");
        assertThatThrownBy(() -> new Limits("8g", null, 1, 0)).hasMessageContaining("lets no file in");
        assertThat(new Limits.Declared(null, null, null, 10L).over(Limits.defaults()).handIn())
                .as("a repository's own hand-in limit over the project's").isEqualTo(10L);
        assertThat(Limits.Declared.none().over(Limits.defaults()).handIn()).isEqualTo(Limits.DEFAULT_HAND_IN);
        assertThatThrownBy(() -> new Limits("8g", null, 0, Limits.DEFAULT_HAND_IN))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("leaves nothing to run");
    }
}
