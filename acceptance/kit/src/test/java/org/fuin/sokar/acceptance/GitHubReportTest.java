package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

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
}
