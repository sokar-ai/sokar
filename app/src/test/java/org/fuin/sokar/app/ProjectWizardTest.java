package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.core.project.SecurityClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProjectWizard}.
 */
class ProjectWizardTest {

    private String create(Path file, String answers) {
        final StringWriter written = new StringWriter();
        try (PrintWriter out = new PrintWriter(written)) {
            ProjectWizard.create(file, new BufferedReader(new StringReader(answers)), out);
        }
        return written.toString();
    }

    @Test
    void derivesANameADirectoryCannotHaveButTheRulesRequire() {

        // A directory is allowed to be called anything; a project name ends up in image tags,
        // container names and nftables set names, which are not.
        assertThat(ProjectWizard.nameFrom(Path.of("/home/you/My Project (v2)")))
                .isEqualTo("my-project-v2");
        assertThat(ProjectWizard.nameFrom(Path.of("/home/you/sokar"))).isEqualTo("sokar");
        assertThat(ProjectWizard.nameFrom(Path.of("/home/you/___"))).isEqualTo("project");
        assertThat(ProjectWizard.nameFrom(Path.of("/home/you/-lead-and-trail-")))
                .isEqualTo("lead-and-trail");
    }

    @Test
    void derivesANameThatIsNotTooLongEvenAfterTruncation() {

        // Truncating at 63 can land on a hyphen, which the rules reject - so the trailing
        // hyphen has to go after the cut, not before it.
        final String name = ProjectWizard.nameFrom(Path.of("/tmp/" + "a".repeat(62) + "-bbbb"));

        assertThat(name).hasSizeLessThanOrEqualTo(63).doesNotEndWith("-");
        assertThat(new Project(name, "d", SecurityClass.GUARDED, "ubuntu:24.04", null, null))
                .isNotNull();
    }

    @Test
    void writesAFileTheReaderAccepts(@TempDir Path directory) {
        final Path file = directory.resolve("project.yml");

        create(file, "\n\n\n\n");

        assertThat(file).exists();
        final Project project = ProjectReader.read(file);
        assertThat(project.name()).isEqualTo(ProjectWizard.nameFrom(directory));
        assertThat(project.securityClass()).isEqualTo(SecurityClass.GUARDED);
        assertThat(project.baseImage()).isEqualTo(ProjectWizard.DEFAULT_BASE_IMAGE);
    }

    @Test
    void takesTheAnswersItIsGiven(@TempDir Path directory) {
        final Path file = directory.resolve("project.yml");

        create(file, "chosen-name\noffline\nfedora:41\ny\n");

        final Project project = ProjectReader.read(file);
        assertThat(project.name()).isEqualTo("chosen-name");
        assertThat(project.securityClass()).isEqualTo(SecurityClass.OFFLINE);
        assertThat(project.baseImage()).isEqualTo("fedora:41");
    }

    @Test
    void writesNothingWhenTheAnswerIsNo(@TempDir Path directory) {
        final Path file = directory.resolve("project.yml");

        final String output = create(file, "\n\n\nn\n");

        assertThat(file).doesNotExist();
        assertThat(output).contains("Nothing written");
    }

    @Test
    void writesNothingWhenTheSecurityClassIsNotOne(@TempDir Path directory) {
        final Path file = directory.resolve("project.yml");

        final String output = create(file, "\nsideways\n\ny\n");

        assertThat(file).doesNotExist();
        assertThat(output).contains("is not a security class");
    }

    @Test
    void offersEveryDefaultSoNobodyHasToKnowThem(@TempDir Path directory) {
        final String output = create(directory.resolve("project.yml"), "\n\n\n\n");

        assertThat(output)
                .contains("[" + ProjectWizard.nameFrom(directory) + "]")
                .contains("[guarded]")
                .contains("[" + ProjectWizard.DEFAULT_BASE_IMAGE + "]");
    }
}
