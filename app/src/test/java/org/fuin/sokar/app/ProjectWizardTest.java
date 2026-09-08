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

    private final org.fuin.sokar.testing.FakeCommandRunner runner =
            new org.fuin.sokar.testing.FakeCommandRunner();

    private SokarContext context(Path directory) {
        final org.fuin.sokar.core.config.XdgPaths xdg =
                org.fuin.sokar.core.config.XdgPaths.of(name -> switch (name) {
                    case "XDG_DATA_HOME" -> directory.resolve("data").toString();
                    case "XDG_RUNTIME_DIR" -> directory.resolve("run").toString();
                    default -> null;
                }, directory);
        return new SokarContext(runner, new SokarPaths(xdg, directory.resolve("bin")),
                command -> 0);
    }

    private String create(Path file, String answers) {
        final StringWriter written = new StringWriter();
        try (PrintWriter out = new PrintWriter(written)) {
            ProjectWizard.create(context(file.getParent()), file,
                    new BufferedReader(new StringReader(answers)), out);
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
    @Test
    void suggestsAStarterEgressSetSoTheFirstBuildWorks() {

        // A guess, and it stays on this side of the handover: sets nobody asked for must not
        // appear in a project created over the contract, where the file is shown for review
        // before anybody agrees to it.
        assertThat(ProjectWizard.suggestedSets("ubuntu:24.04", SecurityClass.GUARDED,
                java.util.Set.of("os-packages-debian", "git-hosting")))
                .containsExactly("os-packages-debian", "git-hosting");
    }

    @Test
    void picksThePackageSetFromTheBaseImage() {

        assertThat(ProjectWizard.suggestedSets("fedora:41", SecurityClass.GUARDED,
                java.util.Set.of("os-packages-fedora", "git-hosting")))
                .contains("os-packages-fedora");
        assertThat(ProjectWizard.suggestedSets("debian:13", SecurityClass.GUARDED,
                java.util.Set.of("os-packages-debian", "git-hosting")))
                .contains("os-packages-debian");
    }

    @Test
    void suggestsOnlyWhatThisMachineHas() {

        // A name that is not installed here used to be written into the file and fail at the
        // first task start. It would now refuse to create the project at all, so it drops out of
        // the offer instead of blocking it.
        assertThat(ProjectWizard.suggestedSets("ubuntu:24.04", SecurityClass.GUARDED,
                java.util.Set.of("git-hosting"))).containsExactly("git-hosting");
        assertThat(ProjectWizard.suggestedSets("ubuntu:24.04", SecurityClass.GUARDED,
                java.util.Set.of())).isEmpty();
    }

    @Test
    void suggestsNothingForAProjectThatReachesNothing() {

        // An offline project resolves nothing at all, so an egress block would be a list of
        // destinations it is not allowed to reach.
        assertThat(ProjectWizard.suggestedSets("ubuntu:24.04", SecurityClass.OFFLINE,
                java.util.Set.of("os-packages-debian"))).isEmpty();
    }
}
