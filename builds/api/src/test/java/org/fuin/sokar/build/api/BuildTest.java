package org.fuin.sokar.build.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BuildTest {

    @Test
    void keepsOnlyTheEndOfALogLongerThanTheTail() {
        final byte[] log = new byte[Build.TAIL + 10];
        Arrays.fill(log, 0, 10, (byte) 'x');
        Arrays.fill(log, 10, log.length, (byte) 'y');

        final byte[] kept = new Build.Job("Build / test", "failure", log).log();

        assertThat(kept).as("the last 64 KiB, which is where a failure is").hasSize(Build.TAIL).doesNotContain('x');
    }

    @Test
    void handsOverTheLogsOfAtMostFiftyJobs() {
        final List<Build.Job> matrix = IntStream.range(0, 200).mapToObj(n -> new Build.Job("j" + n, "success")).toList();

        assertThat(new Build(Build.SUCCESS, matrix, "").jobs()).hasSize(Build.MOST_JOBS);
    }

    @Test
    void refusesAVerdictTheProtocolDoesNotHave() {
        assertThatThrownBy(() -> new Build("passed", "")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passed").hasMessageContaining(Build.SUCCESS);
    }

    @Test
    void readsTheLogPolicyAProjectFileNames() {
        assertThat(Build.Logs.of("failure")).isEqualTo(Build.Logs.FAILURE);
        assertThat(Build.Logs.of("all").wire()).isEqualTo("all");
        assertThatThrownBy(() -> Build.Logs.of("some")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isFinishedOnlyWhenItWillNotChangeAnyMore() {
        assertThat(Build.VERDICTS.stream().filter(verdict -> new Build(verdict, "").finished()))
                .containsExactly(Build.SUCCESS, Build.FAILURE, Build.CANCELLED);
    }

    @Test
    void travelsOverTheProtocolWithItsLogsIntact() {
        final Build failed = new Build(Build.FAILURE, List.of(new Build.Job("Build / lint", "failure",
                new byte[] {0, (byte) 0xff, 'a'}), new Build.Job("Build / docs", "skipped")), "two runs failed");

        assertThat(Build.of(failed.asMap())).isEqualTo(failed);
        assertThat(Build.of(Build.unknown("no build").asMap())).isEqualTo(Build.unknown("no build"));
    }

    @Test
    void neverShowsALogOrATargetsTokenInAString() {
        assertThat(new Build(Build.FAILURE, List.of(new Build.Job("t", "failure",
                "secret line".getBytes(StandardCharsets.UTF_8))), "").toString())
                .doesNotContain("secret line").contains("11 bytes");
        assertThat(new Target("git@forge.example:o/n.git", "", "ghp_token").toString()).doesNotContain("ghp_token");
    }
}
