package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Tests for how long an unlock is kept.
 * <p>
 * A flag nobody can use without reading the help is a flag nobody uses, so what is checked here is
 * that it reads the way somebody types it - and that it refuses clearly rather than guessing.
 */
class VaultUnlockCommandTest {

    @Test
    void readsADurationTheWaySomebodyWritesIt() {

        assertThat(VaultUnlockCommand.duration("45s")).isEqualTo(Duration.ofSeconds(45));
        assertThat(VaultUnlockCommand.duration("30m")).isEqualTo(Duration.ofMinutes(30));
        assertThat(VaultUnlockCommand.duration("8h")).isEqualTo(Duration.ofHours(8));
        assertThat(VaultUnlockCommand.duration(" 8H ")).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void noBoundIsTheDefaultAndStaysTheDefault() {

        // Today's behaviour is unchanged unless somebody asks for a limit. A bound that crept in
        // as a default would start asking people for a passphrase they never used to be asked
        // for, which is a change of behaviour wearing the clothes of a new feature.
        assertThat(VaultUnlockCommand.duration(null)).isNull();
        assertThat(VaultUnlockCommand.duration("")).isNull();
        assertThat(VaultUnlockCommand.duration("   ")).isNull();
    }

    @Test
    void refusesWhatItCannotReadRatherThanGuessing() {

        // ISO-8601 is what a careless implementation would accept, and '30' with no unit is what
        // somebody would actually type. Guessing minutes for the second is how an unlock ends up
        // lasting a different length from what its author meant.
        for (final String bad : new String[] {"30", "PT30M", "half an hour", "30 m", "-5m", "5d"}) {
            assertThatThrownBy(() -> VaultUnlockCommand.duration(bad))
                    .as("input '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("45s, 30m or 8h");
        }
    }

    @Test
    void zeroIsRefusedAndPointsAtTheCommandThatMeansIt() {

        // A key the kernel discards at once reads as "unlock did nothing", which is a bug report
        // rather than a feature. Whoever means it has 'vault lock'.
        assertThatThrownBy(() -> VaultUnlockCommand.duration("0m"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sokar vault lock");
    }
}
