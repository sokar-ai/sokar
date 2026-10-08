package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the one rule that says whether a machine can run a task.
 * <p>
 * Kept separate from the probes themselves, because the rule has to be exercised in both
 * directions and no fixture of a real machine offers both. It is used by the CLI's exit code and
 * by the daemon's answer, so a machine cannot be called ready by one and unready by the other.
 */
class DoctorReadinessTest {

    private static Probe probe(Probe.State state) {
        return state == Probe.State.OK ? new Probe("thing", state, "found", null)
                : new Probe("thing", state, "not found", "install it");
    }

    @Test
    void aMachineWithEverythingIsReady() {
        assertThat(DoctorCommand.ready(List.of(probe(Probe.State.OK), probe(Probe.State.OK))))
                .isTrue();
    }

    @Test
    void anythingMissingMakesItUnready() {

        // A task will fail, or run without something it needs and say nothing - hooks that never
        // load a firewall being the worst of them.
        assertThat(DoctorCommand.ready(List.of(probe(Probe.State.OK), probe(Probe.State.MISSING))))
                .isFalse();
    }

    @Test
    void degradedAndUnknownLeaveAMachineReady() {

        // The machine does run tasks and the report says how well. Treating either as unready
        // would refuse work on a machine that works, and people would stop reading the report.
        assertThat(DoctorCommand.ready(
                List.of(probe(Probe.State.DEGRADED), probe(Probe.State.UNKNOWN)))).isTrue();
    }

    @Test
    void aMachineWithNoProbesAtAllIsNotReportedBroken() {

        // Vacuously true, and deliberately: an empty list means nothing was asked, not that
        // something failed. Answering false here would report a fault nobody could act on.
        assertThat(DoctorCommand.ready(List.of())).isTrue();
    }
}
