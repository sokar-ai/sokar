package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link ShieldEgressCommand}, which edits the highest-consequence line in the project
 * file.
 */
class ShieldEgressCommandTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private int execute(SokarContext context, String... args) {
        final CommandLine cmd = new CommandLine(new SokarCli(), new SokarFactory(context));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    /** Two sets on this machine, so a change can be reported in hosts rather than in names. */
    private void installSets(Path dir) throws IOException {
        final Path sets = dir.resolve("data/sokar/egress");
        Files.createDirectories(sets);
        Files.writeString(sets.resolve("maven.yaml"), """
                name: maven
                label: Maven Central
                domains:
                  - repo.maven.apache.org
                  - central.sonatype.com
                """, StandardCharsets.UTF_8);
        Files.writeString(sets.resolve("git-hosting.yaml"), """
                name: git-hosting
                label: Git hosting
                domains:
                  - github.com
                """, StandardCharsets.UTF_8);
    }

    private Path project(Path dir, String securityClass, String egress) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, """
                # A comment an operator wrote.
                project:
                  name: "uc"
                  security_class: "%s"
                image:
                  base_image: "ubuntu:24.04"
                %s""".formatted(securityClass, egress), StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void showsEveryDestinationWithWhoDecidedIt(@TempDir Path dir) throws IOException {

        installSets(dir);
        final Path file = project(dir, "guarded", """
                egress:
                  sets: [maven]
                  domains: ["nexus.corp.example"]
                """);

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString())).isZero();

        assertThat(out.toString())
                .contains("repo.maven.apache.org").contains("set maven")
                .contains("nexus.corp.example").contains("project")
                .contains("ports 80 and 443");
    }

    @Test
    void saysWhatAChangeOpensBeforeItIsSaved(@TempDir Path dir) throws IOException {

        // In hosts, not in set names: a set is a name for several hosts, and an operator adding
        // one is entitled to see what it opens.
        installSets(dir);
        final Path file = project(dir, "guarded", "");

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--add-set", "maven", "--dry-run")).isZero();

        assertThat(out.toString())
                .contains("opens").contains("repo.maven.apache.org").contains("central.sonatype.com")
                .contains("nothing was written");
        assertThat(Files.readString(file)).doesNotContain("egress");
    }

    @Test
    void writesTheChangeAndSaysWhereAndWhen(@TempDir Path dir) throws IOException {

        installSets(dir);
        final Path file = project(dir, "guarded", "");

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--add-set", "maven", "--add-domain", "nexus.corp.example")).isZero();

        assertThat(Files.readString(file))
                .contains("  sets: [maven]")
                .contains("  domains: [\"nexus.corp.example\"]")
                .contains("# A comment an operator wrote.");
        assertThat(out.toString()).contains("written")
                .contains("applies to the next task, not to one already running");
    }

    @Test
    void saysWhatARemovalCloses(@TempDir Path dir) throws IOException {

        installSets(dir);
        final Path file = project(dir, "guarded", """
                egress:
                  sets: [maven, git-hosting]
                """);

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--remove-set", "git-hosting")).isZero();

        assertThat(out.toString()).contains("closes").contains("github.com");
        assertThat(Files.readString(file)).contains("  sets: [maven]");
    }

    @Test
    void refusesASetThisMachineDoesNotHave(@TempDir Path dir) throws IOException {

        // Written, the file would name something no task on this machine could resolve, and every
        // run would fail on it instead of this one command.
        installSets(dir);
        final Path file = project(dir, "guarded", "");

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--add-set", "nonesuch")).isEqualTo(2);

        assertThat(err.toString()).contains("Unknown egress set 'nonesuch'")
                .contains("sokar shield sets");
        assertThat(Files.readString(file)).doesNotContain("egress");
    }

    @Test
    void refusesToDeclareEgressForAnOfflineProject(@TempDir Path dir) throws IOException {

        // The class forbids it, the reader would refuse the file afterwards, and a file written
        // here that the next command cannot read is the worst of the three outcomes.
        installSets(dir);
        final Path file = project(dir, "offline", "");

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--add-set", "maven")).isEqualTo(2);

        assertThat(err.toString()).contains("offline").contains("can declare no egress");
        assertThat(Files.readString(file)).doesNotContain("egress:");
    }

    @Test
    void sayingWhatIsAlreadyTrueChangesNothing(@TempDir Path dir) throws IOException {

        installSets(dir);
        final Path file = project(dir, "guarded", """
                egress:
                  sets: [maven]
                """);
        final String before = Files.readString(file);

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--add-set", "maven")).isZero();

        assertThat(out.toString()).contains("no change");
        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    void saysWhatReachingAForgeCosts(@TempDir Path dir) throws IOException {

        // The edit that turns the gate from a wall into a convention. Said while it is being made,
        // not only at the top of the next run.
        installSets(dir);
        final Path file = project(dir, "guarded", "");

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--add-set", "git-hosting")).isZero();

        assertThat(out.toString()).contains("cost")
                .contains("github.com is reachable")
                .contains("holding no credential");
    }

    @Test
    void doesNotRepeatTheForgeCostWhenItWasAlreadyPaid(@TempDir Path dir) throws IOException {

        // It was true before the edit and is true after it. Repeating it on an unrelated change
        // teaches an operator to skip the line.
        installSets(dir);
        final Path file = project(dir, "guarded", "egress:\n  sets: [git-hosting]\n");

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--add-set", "maven")).isZero();

        assertThat(out.toString()).doesNotContain("cost");
    }

    @Test
    void listsHostsInTheOrderTheirSourceWasConsulted(@TempDir Path dir) throws IOException {

        // A report whose order changes between runs cannot be diffed against yesterday's, and
        // these are grouped by the set that granted them so an operator can see what a set is.
        installSets(dir);
        final Path file = project(dir, "guarded", "");

        assertThat(execute(context(dir), "shield", "egress", "-p", file.toString(),
                "--add-set", "maven", "--dry-run")).isZero();

        final String report = out.toString();
        assertThat(report.indexOf("repo.maven.apache.org"))
                .isLessThan(report.indexOf("central.sonatype.com"));
    }

    @Test
    void saysSoWhenTheProjectFileCannotBeRead(@TempDir Path dir) {

        assertThat(execute(context(dir), "shield", "egress", "-p",
                dir.resolve("absent.yml").toString())).isEqualTo(2);
        assertThat(err.toString()).contains("sokar:");
    }
}
