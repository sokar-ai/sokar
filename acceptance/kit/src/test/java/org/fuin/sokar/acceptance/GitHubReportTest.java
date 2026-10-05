package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.plugin.event.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link GitHubReport}.
 */
class GitHubReportTest {

    @Test
    void keepsAnAnnotationOnOneLine() {
        // A workflow command ends at the first newline; what follows prints as ordinary output
        // and the annotation arrives as half a message. Found by making a scenario fail on
        // purpose, which the happy path never shows.
        final String line = GitHubReport.annotation("src/test/resources/a.feature", 4,
                "a scenario", "first line\nsecond line");
        assertThat(line).doesNotContain("\n");
        assertThat(line).isEqualTo("::error file=src/test/resources/a.feature,line=4,"
                + "title=a scenario::first line%0Asecond line");
    }

    @Test
    void escapesWhatWouldEndAPropertyEarly() {
        // A comma or a colon in a title would be read as the next property or the message.
        final String line = GitHubReport.annotation("f.feature", 1, "a: b, c", "m");
        assertThat(line).startsWith("::error file=f.feature,line=1,title=a%3A b%2C c::m");
    }

    @Test
    void capsAMessageThatWouldArriveTruncatedAnyway() {
        // Measured at over a kilobyte for one 'doctor' assertion; GitHub truncates somewhere
        // nobody chose, so the cut is made here and says where the rest is.
        final String line = GitHubReport.annotation("f.feature", 1, "s", "x".repeat(2000));
        assertThat(line).endsWith(" ... (see the run log)");
        assertThat(line.length()).isLessThan(1100);
    }

    @Test
    void pointsAtTheFileInTheRepositoryRatherThanOnTheClasspath() {
        // The default is a single-module repository, which is what an agent is. This
        // repository's suite sets sokar.acceptance.features to its own path.
        assertThat(GitHubReport.feature("classpath:org/fuin/sokar/acceptance/x.feature"))
                .isEqualTo("src/test/resources/org/fuin/sokar/acceptance/x.feature");
    }

    @Test
    void saysSoWhenNothingRanRatherThanWritingNothing() {
        // The state this repository was actually in: the suite was left out of the reactor by a
        // module split, so the step passed and the page showed no acceptance section at all -
        // identical to a run where the step was never added.
        final String summary = GitHubReport.summary(new LinkedHashMap<>());
        assertThat(summary).contains("## Acceptance");
        assertThat(summary).contains("**No scenarios ran.**");
    }

    @Test
    void reportsAGreenRunToo() {
        // The whole point of the change: a run with nothing wrong still has to appear, or the
        // page cannot tell 'passed' from 'never ran'.
        final String summary = GitHubReport.summary(oneFeature(
                new GitHubReport.Case("two and two", Status.PASSED, 14)));
        assertThat(summary).contains(":white_check_mark:");
        assertThat(summary).contains("| `a/b.feature` | two and two |");
        assertThat(summary).contains("**1 of 1 passed.**");
        assertThat(summary).doesNotContain(":x:");
    }

    @Test
    void marksTheFailingOneAndCountsIt() {
        final String summary = GitHubReport.summary(oneFeature(
                new GitHubReport.Case("green", Status.PASSED, 1),
                new GitHubReport.Case("red", Status.FAILED, 2)));
        assertThat(summary).contains(":x: | `a/b.feature` | red");
        assertThat(summary).contains("**1 of 2 passed.**");
    }

    @Test
    void collapsesAnOutlineIntoOneRowThatSaysHowManyPassed() {
        // Ten examples are one sentence somebody wrote. Ten identical rows is a summary nobody
        // reads to the end, so the row carries the ratio instead. This is the case where the
        // outline's name has no <placeholders> and Cucumber reports one name for every example;
        // the test below is what happens when it does not.
        final String summary = GitHubReport.summary(oneFeature(
                new GitHubReport.Case("an outline", Status.PASSED, 1),
                new GitHubReport.Case("an outline", Status.FAILED, 2),
                new GitHubReport.Case("an outline", Status.PASSED, 3)));
        assertThat(summary).containsOnlyOnce("an outline");
        assertThat(summary).contains("| 2/3 | 6ms |");
        assertThat(summary).contains("**2 of 3 passed.**");
    }

    @Test
    void leavesExamplesApartWhenCucumberNamedThemApart() {
        // Measured, not assumed: an outline titled 'adding <a> and <b>' reports three distinct
        // names, so nothing folds and each example is its own row. Documented as unconditional
        // folding until a probe run showed otherwise.
        final String summary = GitHubReport.summary(oneFeature(
                new GitHubReport.Case("adding 1 and 1", Status.PASSED, 1),
                new GitHubReport.Case("adding 9 and 9", Status.FAILED, 2)));
        assertThat(summary).contains("| adding 1 and 1 |");
        assertThat(summary).contains("| adding 9 and 9 |");
        assertThat(summary).doesNotContain("/2 |");
        assertThat(summary).contains("**1 of 2 passed.**");
    }

    @Test
    void doesNotCountASkippedScenarioAsAPassedOne() {
        // It said "5 of 5 passed" for a run where four never ran. The absence that causes this -
        // no credential secret, so the credential half skips - is a supported state that a fork
        // hits every time, which makes it the version of the summary most likely to be read.
        final String summary = GitHubReport.summary(oneFeature(
                new GitHubReport.Case("ran", Status.PASSED, 1),
                new GitHubReport.Case("did not run", Status.SKIPPED, 1),
                new GitHubReport.Case("did not run either", Status.SKIPPED, 1)));
        assertThat(summary).contains("**1 of 3 passed.**");
        assertThat(summary).contains("2 skipped");
        assertThat(summary).contains(":fast_forward:");
    }

    @Test
    void saysNothingAboutSkippingWhenNothingWasSkipped() {
        // The common case stays one sentence; a caveat that is always there is not read.
        final String summary = GitHubReport.summary(oneFeature(
                new GitHubReport.Case("ran", Status.PASSED, 1)));
        assertThat(summary).contains("**1 of 1 passed.**");
        assertThat(summary).doesNotContain("skipped");
    }

    @Test
    void countsFailedAndSkippedApartFromEachOther() {
        final String summary = GitHubReport.summary(oneFeature(
                new GitHubReport.Case("ran", Status.PASSED, 1),
                new GitHubReport.Case("broke", Status.FAILED, 1),
                new GitHubReport.Case("did not run", Status.SKIPPED, 1)));
        assertThat(summary).contains("**1 of 3 passed.**");
        assertThat(summary).contains("1 skipped");
        assertThat(summary).contains("1 failed.");
    }

    private static Map<String, List<GitHubReport.Case>> oneFeature(
            final GitHubReport.Case... cases) {
        final Map<String, List<GitHubReport.Case>> byFeature = new LinkedHashMap<>();
        byFeature.put("x/a/b.feature", List.of(cases));
        return byFeature;
    }

    @Test
    void aScenarioWhoseStepsNoLongerExistIsNotCountedAsPassed() {
        // A renamed kit step left an agent repository's scenarios undefined; the summary said all of them passed.
        final String summary = GitHubReport.summary(oneFeature(
                new GitHubReport.Case("green", Status.PASSED, 1),
                new GitHubReport.Case("renamed", Status.UNDEFINED, 0)));
        assertThat(summary).contains("**1 of 2 passed.**").contains("1 did not run");
        assertThat(GitHubReport.annotated(Status.UNDEFINED)).isTrue();
        assertThat(GitHubReport.annotated(Status.SKIPPED)).isFalse();
        assertThat(GitHubReport.annotated(Status.PASSED)).isFalse();
    }
}
