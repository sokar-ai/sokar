package org.fuin.sokar.machines;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the guard that stops a leg reporting success on nothing.
 */
class LegTest {

    @Test
    void refusesARunThatProducedNoResultsAtAll(@TempDir Path reports) {
        // A suite that selects nothing passes, and a page with no acceptance section looks
        // exactly like one where the step was never added. This repository sat in that state
        // for several merges and nothing said so.
        assertThatThrownBy(() -> Leg.proved(reports))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ran no scenarios");
    }

    @Test
    void refusesADirectoryThatIsNotThere(@TempDir Path parent) {
        assertThatThrownBy(() -> Leg.proved(parent.resolve("never-written")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ran no scenarios");
    }

    @Test
    void refusesReportsThatRanNothing(@TempDir Path reports) throws IOException {
        // A tag filter that excludes everything writes a report saying zero.
        Files.writeString(reports.resolve("TEST-a.xml"), "<testsuite tests=\"0\"/>");
        assertThatThrownBy(() -> Leg.proved(reports))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ran no scenarios");
    }

    @Test
    void acceptsAsSoonAsSomethingActuallyRan(@TempDir Path reports) throws IOException {
        Files.writeString(reports.resolve("TEST-a.xml"), "<testsuite tests=\"0\"/>");
        Files.writeString(reports.resolve("TEST-b.xml"), "<testsuite tests=\"7\"/>");
        assertThatCode(() -> Leg.proved(reports)).doesNotThrowAnyException();
    }

    @Test
    void ignoresFilesThatAreNotReports(@TempDir Path reports) throws IOException {
        // failsafe leaves .txt summaries beside the XML; counting those would be counting twice.
        Files.writeString(reports.resolve("TEST-a.txt"), "not xml");
        Files.writeString(reports.resolve("something.xml"), "<testsuite tests=\"9\"/>");
        assertThatThrownBy(() -> Leg.proved(reports))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("ran no scenarios");
    }

    @Test
    void namesWhereItLooked(@TempDir Path reports) {
        assertThat(Leg.class).isNotNull();
        assertThatThrownBy(() -> Leg.proved(reports))
                .hasMessageContaining(reports.toString());
    }
}
