package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Test for {@link TaskWatchBuildsCommand#waitFor}: the builds helper's wait ends when the vault changes.
 */
class BuildWaitTest {

    @Test
    void aWaitAfterARefusalEndsOnceTheVaultChangesNotAfterItsTenMinutes() throws InterruptedException {

        // A person stored a new token, and the helper asked GitHub again only ten minutes
        // after the refusal - and again ten minutes later after the next one.
        final List<Duration> slept = new ArrayList<>();
        final int[] looks = {0};

        TaskWatchBuildsCommand.waitFor(BuildWatch.REFUSED, () -> ++looks[0] >= 3 ? "changed" : "as before",
                slept::add);

        assertThat(slept).as("two steps, then the vault had changed").hasSize(2)
                .allSatisfy(step -> assertThat(step).isEqualTo(TaskWatchBuildsCommand.STEP));
    }

    @Test
    void aWaitWithNothingChangedTakesItsWholeTime() throws InterruptedException {
        final List<Duration> slept = new ArrayList<>();

        TaskWatchBuildsCommand.waitFor(Duration.ofSeconds(12), () -> "as before", slept::add);

        assertThat(slept.stream().mapToLong(Duration::toMillis).sum()).isEqualTo(12_000);
    }
}
