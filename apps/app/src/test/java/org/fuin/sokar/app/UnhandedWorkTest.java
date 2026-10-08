package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link UnhandedWork}.
 */
class UnhandedWorkTest {

    private static void wrote(Path state, String content) throws IOException {
        Files.writeString(state.resolve(UnhandedWork.FILE), content);
    }

    @Test
    void carriesTheCountsBackRatherThanASentence(@TempDir Path state) {

        UnhandedWork.note(state, new UnhandedWork.Held(true, 3, 2, null));

        final UnhandedWork.Held read = UnhandedWork.read(state);
        assertThat(read.readable()).isTrue();
        assertThat(read.changedFiles()).isEqualTo(3);
        assertThat(read.unpushedCommits()).isEqualTo(2);
        assertThat(read.asOf()).as("a recorded answer is historical and says so").isNotNull();
    }

    @Test
    void tellsHoldingNothingApartFromNobodyLooking(@TempDir Path state) {

        // The whole reason a note is written even when there is nothing. Only one of these two
        // makes it safe to remove a task without asking.
        UnhandedWork.note(state, new UnhandedWork.Held(true, 0, 0, null));
        assertThat(UnhandedWork.read(state).readable()).isTrue();
        assertThat(UnhandedWork.read(state).anything()).isFalse();

        assertThat(UnhandedWork.read(state.resolve("no-note-here")).readable()).isFalse();
    }

    @Test
    void readsBackANoteAnOlderSokarWrote(@TempDir Path state) throws IOException {

        // The old format recorded the sentence. Parsed rather than discarded: it is our own
        // wording in one of three shapes, and answering "nobody looked" about a task that was
        // measured would destroy work this exists to protect.
        wrote(state, "2 commits and 3 changed files");
        assertThat(UnhandedWork.read(state)).satisfies(held -> {
            assertThat(held.readable()).isTrue();
            assertThat(held.unpushedCommits()).isEqualTo(2);
            assertThat(held.changedFiles()).isEqualTo(3);
            assertThat(held.asOf()).as("taken from the file, the only thing that knows").isNotNull();
        });

        wrote(state, "1 commit");
        assertThat(UnhandedWork.read(state).unpushedCommits()).isEqualTo(1);
        assertThat(UnhandedWork.read(state).changedFiles()).isZero();

        wrote(state, "1 changed file");
        assertThat(UnhandedWork.read(state).changedFiles()).isEqualTo(1);
        assertThat(UnhandedWork.read(state).unpushedCommits()).isZero();
    }

    @Test
    void treatsAnEmptyOldNoteAsHavingHeldNothing(@TempDir Path state) throws IOException {

        // That is what an empty note meant, and it meant it deliberately.
        wrote(state, "");
        assertThat(UnhandedWork.read(state).readable()).isTrue();
        assertThat(UnhandedWork.read(state).anything()).isFalse();
    }

    @Test
    void refusesANoteFromALaterSokar(@TempDir Path state) throws IOException {

        // A misread answer here decides whether work is destroyed, so an unknown version is
        // "nobody looked" rather than a guess - the same rule the sidecar follows.
        wrote(state, "{\"version\":99,\"changedFiles\":0,\"unpushedCommits\":0}");
        assertThat(UnhandedWork.read(state).readable()).isFalse();
    }

    @Test
    void refusesANoteItCannotMakeSenseOf(@TempDir Path state) throws IOException {

        wrote(state, "something nobody wrote on purpose");
        assertThat(UnhandedWork.read(state).readable()).isFalse();
        wrote(state, "{not json");
        assertThat(UnhandedWork.read(state).readable()).isFalse();
    }

    @Test
    void answersUnknownWhenTheContainerSaidSomethingUnusable() {

        // Two numbers or nothing. A container that answered something else is not a container
        // that answered "nothing".
        assertThat(UnhandedWork.census("3 2").readable()).isTrue();
        assertThat(UnhandedWork.census("").readable()).isFalse();
        assertThat(UnhandedWork.census("lots").readable()).isFalse();
        assertThat(UnhandedWork.census("a b").readable()).isFalse();
    }

    @Test
    void wordsTheCountsOnlyWhereSomebodyReadsThem() {

        assertThat(new UnhandedWork.Held(true, 3, 2, null).phrase())
                .isEqualTo("2 commits and 3 changed files");
        assertThat(new UnhandedWork.Held(true, 1, 1, null).phrase())
                .isEqualTo("1 commit and 1 changed file");
        assertThat(new UnhandedWork.Held(true, 0, 0, null).phrase()).isNull();
        assertThat(UnhandedWork.Held.unknown().phrase()).isNull();
    }
}
