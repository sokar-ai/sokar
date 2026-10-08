package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.AgentDefinitionReader;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.project.Mail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link TaskGuide} and {@link TaskGuideText}.
 */
class TaskGuideTest {

    private static final String TASK = "sokar-p-writer";

    private static final AgentDefinition CLAUDE = AgentDefinitionReader.read(new StringReader("""
            name: claude
            binary: claude
            git_identity: { name: C, email: c@example.com }
            headless: { prompt_flag: "-p" }
            instructions: { arguments: ["--append-system-prompt-file", "{file}"] }
            """), "claude.yaml");

    @TempDir
    Path dir;

    private SokarPaths paths() {
        return new SokarPaths(XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir), dir.resolve("bin"));
    }

    @Test
    void tellsEveryAgentOfItsFilesAndTheBuildsOfItsPushes() {

        // An agent pushed and never looked at its red build, since nothing told it of /sokar/files.
        assertThat(TaskGuideText.text(false)).contains(org.fuin.sokar.runtime.Containerfile.HAND_IN_FILES)
                .contains("build-<commit>.txt").contains("build-<commit>-<n>.log").contains("verdict")
                .contains("git push sokar").contains("$SOKAR_TASK_REF").contains("name no branch").contains("git fetch sokar").contains("failure").contains("whenever you come to rest").contains("When your work is done, commit it and push it with `git push sokar`").doesNotContain(Mailbox.MOUNT);
        assertThat(TaskGuideText.text(true)).as("the mailbox's part where there is one")
                .startsWith(TaskGuideText.text(false)).contains(MailboxGuide.text());
    }

    @Test
    void isTheSameForEveryTaskSoAProvidersCacheOfThePromptHolds() {
        assertThat(TaskGuideText.text(true)).isEqualTo(TaskGuideText.text(true)).doesNotContain("sokar-");
    }

    @Test
    void isWrittenWhereTheTaskReadsItAndGivenToTheAgentFromThere() throws IOException {
        final TaskGuide guide = new TaskGuide(paths(), TASK);
        assertThat(guide.instructing(CLAUDE, List.of("claude"))).as("neither guide").containsExactly("claude");

        TaskGuideText.write(guide, false);

        assertThat(guide.file()).hasContent(TaskGuideText.text(false));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(guide.file()))).isEqualTo("rw-r--r--");
        assertThat(guide.directory()).as("in the state directory, so a reboot keeps what is mounted")
                .startsWith(dir.resolve("state"));
        assertThat(guide.instructing(CLAUDE, List.of("claude")))
                .containsExactly("claude", "--append-system-prompt-file", TaskGuide.GUIDE);
    }

    @Test
    void aTaskMadeBeforeTheGuideKeepsItsMailboxsGuide() throws IOException {
        final SokarPaths paths = paths();
        final Mailbox mailbox = new Mailbox(paths.messaging().mailbox(TASK));
        mailbox.create();
        MailboxGuide.write(mailbox, Mail.none());

        assertThat(new TaskGuide(paths, TASK).instructing(CLAUDE, List.of("claude")))
                .as("its container has no mount for the guide").containsExactly("claude",
                        "--append-system-prompt-file", Mailbox.GUIDE);
    }

    @Test
    @Tag("documents")
    void theUserDocumentationShowsTheTextAgentsAreGivenWordForWord() throws IOException {
        final String documented = Files.readString(Path.of("..", "..", "doc", "running.md"));

        for (final String line : TaskGuideText.text(false).split("\n")) {
            assertThat(documented).as("doc/running.md").contains(line.startsWith("#") ? "#" + line : line);
        }
    }
}
