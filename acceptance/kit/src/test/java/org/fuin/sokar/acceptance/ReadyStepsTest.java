package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReadyStepsTest {

    @Test
    void readsTheMarkerAndItsBoundFromWhatTheAgentDescribes() {
        assertThat(ReadySteps.ready("{\"version\":1,\"definition\":{\"name\":\"a\",\"readyMarker\":\"at work\","
                + "\"readyWithinSeconds\":30}}"))
                .contains(new ReadySteps.Ready("at work", Duration.ofSeconds(30)));
    }

    @Test
    void takesTheDefaultBoundWhenTheAgentStatesNone() {
        assertThat(ReadySteps.ready("{\"definition\":{\"readyMarker\":\"at work\"}}"))
                .contains(new ReadySteps.Ready("at work", ReadySteps.DEFAULT_BOUND));
    }

    @Test
    void findsNothingForAnAgentThatDeclaresNoMarker() {
        // Which makes the step say it cannot tell, rather than pass on nothing.
        assertThat(ReadySteps.ready("{\"definition\":{\"name\":\"a\"}}")).isEmpty();
    }

    @Test
    void boundsAnUnattendedRunWithTimeout() {
        assertThat(ReadySteps.boundedCommand("live", "pi", 90)).startsWith("timeout 90 sokar task start --project 'live'");
    }

    @Test
    void refusesABoundOfNothing() {
        assertThatThrownBy(() -> ReadySteps.boundedCommand("live", "pi", 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAProviderThatIsNotAName() {
        // Quoted where it goes; refused before, so a scenario naming nonsense fails in the kit, not the shell.
        assertThatThrownBy(() -> TaskSteps.aName("open router; rm")).isInstanceOf(IllegalArgumentException.class);
    }
}
