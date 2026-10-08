package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link WorkspaceSetup#prepareWorkspace}: an online task whose workspace fetched nothing does not start.
 */
class OnlineWorkspaceTest {

    @TempDir
    Path dir;

    @Test
    void anOnlineWorkspaceThatFetchedNothingFailsTheStartNamingGitsWordsAndTheKey() {

        // A task started on an empty repository; its agent could not see the code.
        final String nothing = prepare("sokar-fetch-failed 128\nsokar-fetch-said git@github.com: Permission denied"
                + " (publickey).\nsokar-workspace-empty\n");

        assertThat(nothing).as("the start fails").isNotNull().contains("git@github.com:sokar-ai/sokar-test-2.git")
                .contains("Permission denied (publickey)").contains("no ssh key was lent");
    }

    @Test
    void anOnlineWorkspaceWithWorkStartsWhateverTheFetchDid() {
        assertThat(prepare("sokar-fetch-failed 128\nsokar-fetch-said fatal: unreachable\n")).isNull();
    }

    private String prepare(final String said) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarPaths paths = new SokarPaths(xdg, dir.resolve("bin"));
        final SokarContext context = new SokarContext(new ProcessCommandRunner(), paths, arguments -> 0);
        final TaskRunner runner = new TaskRunner(new ProcessCommandRunner(), paths) {
            @Override
            public int execute(final String container, final Map<String, String> environment,
                    final List<String> command, final Path log, final Duration timeout) {
                try {
                    Files.createDirectories(log.getParent());
                    Files.writeString(log, said);
                } catch (java.io.IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
                return 0;
            }
        };
        return new WorkspaceSetup(context, "red", null).prepareWorkspace(runner,
                TaskWorkspace.direct("git@github.com:sokar-ai/sokar-test-2.git"), "sokar-p-red", Map.of(),
                new PrintWriter(new StringWriter(), true), new PrintWriter(new StringWriter(), true));
    }
}
