package org.fuin.sokar.clearance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClearanceRequest}, which is what an operator reads before deciding.
 */
class ClearanceRequestTest {

    @Test
    void namesTheTaskRatherThanTheContainer() {

        // Captured off the notification bus: the summary read
        // "Sokar: sokar-notify-probe-shell-1209592 blocked", which carries a process id and
        // identifies nothing an operator with several tasks open would recognize.
        assertThat(new ClearanceRequest("myproject", "shell", "1.1.1.1:443", "1.1.1.1", "tcp")
                .summary()).isEqualTo("Sokar: myproject/shell blocked");
    }

    @Test
    void saysWhatWasReachedForAndHow() {
        assertThat(new ClearanceRequest("p", "t", "api.example.test:443 (1.1.1.1)", "1.1.1.1", "tcp")
                .body()).isEqualTo(
                        "The agent tried to reach api.example.test:443 (1.1.1.1) over tcp.");
    }

    @Test
    void saysWhatTheSilenceDidWhenNobodyAnswered() {

        // A question that simply disappears leaves an operator who was away from the machine
        // unable to tell it from one that was never raised - and this one settled something: the
        // destination stays blocked and is not asked about again.
        final ClearanceRequest request =
                new ClearanceRequest("myproject", "shell", "1.1.1.1:443", "1.1.1.1", "tcp");

        assertThat(request.expiredSummary()).isEqualTo("Sokar: myproject/shell not answered");
        assertThat(request.expiredBody()).isEqualTo(
                "1.1.1.1:443 over tcp stays blocked. It will not be asked again for this task.");
    }

    @Test
    void showsTheAddressWhenNothingNamedIt() {

        // The common case, and the honest one: an agent dialling a literal address was never
        // told a name, and inventing one would be worse than the number.
        assertThat(new ClearanceRequest("p", "t", "1.1.1.1:443", "1.1.1.1", "tcp").body())
                .contains("1.1.1.1:443");
    }
}
