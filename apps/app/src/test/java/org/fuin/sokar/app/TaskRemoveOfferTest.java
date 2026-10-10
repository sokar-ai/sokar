package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests what {@code sokar task remove} offers at a terminal where it refused and named an option: stop a running task,
 * rescue or discard its work, start a stopped one to rescue it, discard what nobody recorded - the destructive ones
 * defaulting to no and never taken by {@code --yes}.
 */
class TaskRemoveOfferTest {

    private final StringWriter err = new StringWriter();

    private final List<String> asked = new ArrayList<>();

    private Offer offer(final String answer, final boolean yes) {
        return new Offer(question -> {
            asked.add(question);
            return answer;
        }, false, yes, new PrintWriter(err, true));
    }

    @Test
    void aRunningTaskIsOfferedToBeStoppedFirst() {
        assertThat(TaskRemoveCommand.nextTry(TaskControl.Outcome.STILL_RUNNING, "t", "", offer("", false)))
                .isEqualTo("stop");
        assertThat(asked).singleElement().asString().endsWith("[Y/n]");
    }

    @Test
    void workItHoldsIsRescuedDiscardedOrKeptAsThePersonChooses() {
        assertThat(TaskRemoveCommand.nextTry(TaskControl.Outcome.HOLDS_WORK, "t", "2 commits", offer("1", false)))
                .isEqualTo("rescue");
        assertThat(TaskRemoveCommand.nextTry(TaskControl.Outcome.HOLDS_WORK, "t", "2 commits", offer("2", false)))
                .isEqualTo("force");
        assertThat(TaskRemoveCommand.nextTry(TaskControl.Outcome.HOLDS_WORK, "t", "2 commits", offer("3", false)))
                .isNull();
        assertThat(err.toString()).contains("2 commits");
    }

    @Test
    void discardingWhatNobodyRecordedDefaultsToNoAndIsNeverTakenByYes() {
        assertThat(TaskRemoveCommand.nextTry(TaskControl.Outcome.NOTHING_KNOWS, "t", "", offer("", true))).isNull();
        assertThat(asked).singleElement().asString().endsWith("[y/N]");
        assertThat(TaskRemoveCommand.nextTry(TaskControl.Outcome.NOTHING_KNOWS, "t", "", offer("y", false)))
                .isEqualTo("force");
    }

    @Test
    void aStoppedTaskWhoseWorkIsToBeRescuedIsOfferedToBeStarted() {
        assertThat(TaskRemoveCommand.nextTry(TaskControl.Outcome.RESCUE_NEEDS_IT_RUNNING, "t", "", offer("y", false)))
                .isEqualTo("start");
    }

    @Test
    void nothingElseIsOffered() {
        assertThat(TaskRemoveCommand.nextTry(TaskControl.Outcome.REMOVED, "t", "", offer("y", false))).isNull();
        assertThat(asked).isEmpty();
    }
}
