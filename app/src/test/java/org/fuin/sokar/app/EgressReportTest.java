package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.project.Egress;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.shield.EgressSetDirectory;
import org.fuin.sokar.shield.EgressSetException;
import org.junit.jupiter.api.Test;

/**
 * Tests for what a task reports it may reach, and where each destination came from.
 * <p>
 * Against the sets this repository actually ships, not a fixture copy of them: the report is only
 * worth anything if the names in a project file resolve to the hosts an operator was shown.
 */
class EgressReportTest {

    private static final EgressSetDirectory SETS =
            new EgressSetDirectory(List.of(Path.of("..", "egress")));

    private static Project project(SecurityClass securityClass, Egress egress) {
        return new Project("demo", "", securityClass, "ubuntu:24.04", null,
                securityClass == SecurityClass.ONLINE ? "git@github.com:you/demo.git" : null,
                org.fuin.sokar.core.project.Limits.defaults(), egress);
    }

    private static String report(Project project, Map<String, String> origins,
            Map<String, String> refused) {
        final StringWriter written = new StringWriter();
        final PrintWriter out = new PrintWriter(written);
        EgressReport.reportReachable(project, origins, refused, out);
        out.flush();
        return written.toString();
    }

    @Test
    void namesTheSetThatGrantedEachHost() {

        final Map<String, String> origins = EgressReport.projectEgress(
                project(SecurityClass.GUARDED, new Egress(List.of("maven"), List.of())), SETS);

        assertThat(origins).containsEntry("repo.maven.apache.org", "set maven")
                .containsEntry("central.sonatype.com", "set maven");
    }

    @Test
    void aDirectlyNamedHostIsReportedAsTheOperatorsOwnDecision() {

        // It may also appear in a set. Whoever wrote it down by hand should see it back as their
        // own decision rather than as whichever set happens to contain it too.
        final Map<String, String> origins = EgressReport.projectEgress(project(
                SecurityClass.GUARDED,
                new Egress(List.of("maven"), List.of("repo.maven.apache.org"))), SETS);

        assertThat(origins).containsEntry("repo.maven.apache.org", "project");
    }

    @Test
    void aProjectThatDeclaresNothingGrantsNothing() {

        assertThat(EgressReport.projectEgress(
                project(SecurityClass.GUARDED, Egress.none()), SETS)).isEmpty();
    }

    @Test
    void anUnknownSetStopsTheTask() {

        assertThatThrownBy(() -> EgressReport.projectEgress(
                project(SecurityClass.GUARDED, new Egress(List.of("mvn"), List.of())), SETS))
                .isInstanceOf(EgressSetException.class)
                .hasMessageContaining("Unknown egress set 'mvn'");
    }

    @Test
    void reportsEveryDestinationWithWhoDecidedIt() {

        final String report = report(project(SecurityClass.GUARDED, Egress.none()),
                new java.util.LinkedHashMap<>(Map.of()) {{
                    put("platform.claude.com", "agent claude");
                    put("api.anthropic.com", "provider anthropic");
                    put("repo.maven.apache.org", "set maven");
                    put("nexus.corp.example", "project");
                }}, Map.of());

        // Padded to the longest host, so the pairing is asserted rather than the spacing.
        assertThat(report.lines().map(line -> line.replaceAll("\\s+", " ").strip()))
                .contains("reachable platform.claude.com agent claude",
                        "api.anthropic.com provider anthropic",
                        "repo.maven.apache.org set maven",
                        "nexus.corp.example project");
        assertThat(report).contains("ports 80 and 443");
    }

    @Test
    void keepsARefusedDestinationDistinctFromOneNobodyAdded() {

        // Two different states. Only one of them is something to go and fix, and a report that
        // showed neither would leave an operator guessing which they were looking at.
        final String report = report(project(SecurityClass.GUARDED, Egress.none()),
                Map.of("example.com", "agent stub"), Map.of("example.net", "agent stub"));

        assertThat(report.lines().map(line -> line.replaceAll("\\s+", " ").strip()))
                .contains("reachable example.com agent stub", "example.net refused on purpose by agent stub")
                .contains("a refusal is of a name: an address a refused host shares with an allowed one stays reachable");
    }

    @Test
    void saysSoWhenThereIsNothingToReach() {

        assertThat(report(project(SecurityClass.GUARDED, Egress.none()), Map.of(), Map.of()))
                .contains("nothing - no agent, provider or project declared a host");
    }

    @Test
    void warnsWhenAGuardedProjectCanReachAForge() {

        final String report = report(project(SecurityClass.GUARDED, Egress.none()),
                Map.of("github.com", "set git-hosting"), Map.of());

        assertThat(report).contains("github.com is reachable")
                .contains("no credential for them");
    }

    @Test
    void doesNotWarnWhenThereIsNoGateToUndermine() {

        // An online project's agent already pushes to the upstream itself, so there is no review
        // step for a reachable forge to weaken. Warning there would be noise.
        assertThat(report(project(SecurityClass.ONLINE, Egress.none()),
                Map.of("github.com", "set git-hosting"), Map.of()))
                .doesNotContain("no credential for them");
    }

    @Test
    void warnsAboutASubdomainOfAForgeToo() {

        assertThat(report(project(SecurityClass.GUARDED, Egress.none()),
                Map.of("raw.githubusercontent.com", "set git-hosting"), Map.of()))
                .contains("raw.githubusercontent.com is reachable");
    }

    @Test
    void namesOneForgeAndCountsTheRest() {

        // A whole set is eight hosts, and eight names in one sentence is a line nobody reads.
        final String report = report(project(SecurityClass.GUARDED, Egress.none()),
                new java.util.LinkedHashMap<>() {{
                    put("github.com", "set git-hosting");
                    put("gitlab.com", "set git-hosting");
                    put("codeberg.org", "set git-hosting");
                }}, Map.of());

        assertThat(report).contains("github.com and 2 more forge hosts are reachable")
                .doesNotContain("gitlab.com,");
    }

    @Test
    void aRefusalSaysWhoMadeItTheAgentFirst() {
        // One declaration read once, for the report and the resolver alike.
        final Project project = project(SecurityClass.GUARDED,
                new Egress(List.of(), List.of("iana.org"), List.of("www.iana.org", "example.net")));

        assertThat(EgressReport.refusals(null, project, project.ownRepository()))
                .containsExactly(Map.entry("www.iana.org", "project"), Map.entry("example.net", "project"));
    }
}
