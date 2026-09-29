package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.gate.GitGate;
import org.fuin.sokar.gate.ReviewRanking;
import org.fuin.sokar.wire.TaskMode;
import org.fuin.sokar.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ReviewText}: what a person reads before the patch.
 */
class ReviewTextTest {

    @TempDir
    Path dir;

    private SokarPaths paths() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarPaths(xdg, dir.resolve("bin"));
    }

    private void task(Path directory, String prompt, String push) throws IOException {
        Files.createDirectories(directory);
        new TaskProfile(1, "asker", TaskMode.UNATTENDED, prompt, GitGate.INCOMING + push,
                "2026-09-29T12:00:00Z", "deny").writeTo(directory);
    }

    @Test
    void findsWhatTheTaskThatPushedWasAsked() throws IOException {
        task(paths().containerState("sokar-checkout-fix"), "fix the flaky test", "fix");

        final ReviewText.Instruction asked = ReviewText.instruction(paths(), "checkout", "fix");

        assertThat(asked.found()).isTrue();
        assertThat(asked.describe()).isEqualTo("fix the flaky test");
    }

    @Test
    void findsItInTheCopyKeptAfterARestartToo() throws IOException {
        task(paths().taskRecord("sokar-checkout-fix"), "fix the flaky test", "fix");

        assertThat(ReviewText.instruction(paths(), "checkout", "fix").describe()).isEqualTo("fix the flaky test");
    }

    @Test
    void saysSoWhenTheTaskWasStartedWithoutAnInstruction() throws IOException {
        task(paths().containerState("sokar-checkout-fix"), "", "fix");

        assertThat(ReviewText.instruction(paths(), "checkout", "fix").describe()).startsWith("none:");
    }

    @Test
    void saysSoWhenTheTaskIsGoneRatherThanShowingNothing() {
        assertThat(ReviewText.instruction(paths(), "checkout", "fix").describe()).startsWith("not known:");
    }

    @Test
    void namesTheDangerousFirstAndSaysSoWhenThereIsNone() {
        final String withDanger = ReviewText.render(new ReviewText.Instruction(true, "fix it"), List.of(
                new ReviewRanking.File(".github/workflows/ci.yml", "M", 1, 0, ReviewRanking.Rank.DANGEROUS,
                        "a CI definition: it runs with the CI's credentials"),
                new ReviewRanking.File("App.java", "M", 3, 1, ReviewRanking.Rank.ORDINARY, ""),
                new ReviewRanking.File("Big.java", "M", 400, 400, ReviewRanking.Rank.REFORMATTING, "whitespace only")));

        assertThat(withDanger).startsWith("asked: fix it\n\nREAD FIRST - dangerous by kind, however small:\n"
                + "  M  .github/workflows/ci.yml  +1 -0  a CI definition");
        assertThat(withDanger.indexOf("App.java")).isLessThan(withDanger.indexOf("Big.java"));
        assertThat(withDanger).contains("Almost never the finding - at the end of the patch:\n"
                + "  M  Big.java  +400 -400  whitespace only");

        final String without = ReviewText.render(ReviewText.Instruction.GONE,
                List.of(new ReviewRanking.File("App.java", "M", 3, 1, ReviewRanking.Rank.ORDINARY, "")));
        assertThat(without).contains("Nothing dangerous by kind");
    }
}
