package org.fuin.sokar.release;

import static org.assertj.core.api.Assertions.assertThat;

import org.fuin.sokar.release.Versions.Verdict;
import org.junit.jupiter.api.Test;

class VersionsTest {

    @Test
    void aNewerUpstreamIsAnUpdate() {
        assertThat(Versions.verdict("2.1.236", "2.1.267", false)).isEqualTo(Verdict.YES);
    }

    @Test
    void theSameVersionIsNothingToDo() {
        assertThat(Versions.verdict("2.1.267", "2.1.267", false)).isEqualTo(Verdict.NO);
    }

    @Test
    void anOlderUpstreamFromAPointerIsARollbackNotAnUpdate() {
        assertThat(Versions.verdict("2.1.267", "2.1.236", false)).isEqualTo(Verdict.ROLLBACK);
    }

    @Test
    void anOlderVersionAPersonNamedMayBePinned() {
        assertThat(Versions.verdict("2.1.267", "2.1.236", true)).isEqualTo(Verdict.YES);
    }

    @Test
    void comparesByNumberSoNineComesBeforeTen() {
        assertThat(Versions.verdict("2.1.9", "2.1.10", false)).isEqualTo(Verdict.YES);
        assertThat(Versions.verdict("2.1.10", "2.1.9", false)).isEqualTo(Verdict.ROLLBACK);
    }

    @Test
    void saysWhenTheMajorVersionMoved() {
        assertThat(Versions.majorMoved("18.1.13", "19.0.0")).isTrue();
        assertThat(Versions.majorMoved("18.1.13", "18.2.0")).isFalse();
    }

    @Test
    void onlyThreeNumbersAreAVersion() {
        assertThat(Versions.isVersion("2.1.267")).isTrue();
        assertThat(Versions.isVersion("v2.1.267")).isFalse();
        assertThat(Versions.isVersion("2.1.267\n<html>")).isFalse();
        assertThat(Versions.isVersion("${agent.cli.version}")).isFalse();
        assertThat(Versions.isVersion("")).isFalse();
    }

    @Test
    void theVerdictReadsAsTheWordAWorkflowCompares() {
        assertThat(Verdict.ROLLBACK.word()).isEqualTo("rollback");
    }

}
