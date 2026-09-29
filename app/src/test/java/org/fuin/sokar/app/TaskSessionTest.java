package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.GitIdentity;
import org.fuin.sokar.agent.api.HeadlessFlags;
import org.fuin.sokar.agent.api.SessionIds;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TaskSession}: the session a task's agent was running, recorded where a reboot does not reach
 * it, and continued only where the agent says how.
 */
class TaskSessionTest {

    private static final String TASK = "sokar-p-t";

    private static final SessionIds IDS = new SessionIds(Map.of("kind", "session"), "id", ".sessions", ".session");

    @TempDir
    Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static AgentDefinition agent(boolean resumes, SessionIds ids) {
        return new AgentDefinition("asker", "Asker", "asker-cli", new GitIdentity("A", "a@example.invalid"),
                new HeadlessFlags("", null, null, null, List.of()), resumes, resumes ? "--resume" : null, Map.of(), null,
                List.of(), List.of(), null, List.of(), List.of(), List.of(), List.of(), null, null, null, List.of(), null,
                null, ids);
    }

    @Test
    void aRecordedSessionSurvivesUntilTheTaskIsRemovedAndLivesWhereARebootDoesNotReach() {
        final SokarContext context = context();
        final TaskSession sessions = new TaskSession(context);

        sessions.record(TASK, "s-1");

        assertThat(sessions.recorded(TASK)).contains("s-1");
        assertThat(context.paths().sessionRecord(TASK)).startsWith(dir.resolve("state"));
        assertThat(context.paths().sessionRecord(TASK).startsWith(dir.resolve("run"))).isFalse();
        sessions.forget(TASK);
        assertThat(sessions.recorded(TASK)).isEmpty();
    }

    @Test
    void theIdAnUnattendedRunNamedIsReadFromItsRecords() throws IOException {
        final Path log = Files.writeString(dir.resolve("task.log"), """
                stub output that is not a record
                {"kind":"session","id":"s-42"}
                {"kind":"result","said":"done"}
                """);

        assertThat(TaskSession.fromRun(log, IDS)).contains("s-42");
    }

    @Test
    void theNewestSessionFileOfAnAttachedAgentNamesItsSession() {
        runner.answering("find", "/home/agent/.sessions/project/s-77.session\n");

        assertThat(new TaskSession(context()).fromFiles(TASK, IDS)).contains("s-77");
        // Looked up by the host in the container, under the agent's home, by the declared suffix.
        assertThat(runner.invocations()).first().asString().contains("$HOME/.sessions").contains("'*.session'");
    }

    @Test
    void theIdIsReadFromInsideTheNewestFileWhenItsNameIsNotOne() {
        runner.answering("find", "/home/agent/.sessions/project/2026-09-29T10-00-00_s-88.session\n");
        runner.answering("-n 50", """
                {"kind":"title","id":"not-this-one"}
                {"kind":"session","id":"s-88"}
                """);

        // The first record of the declared kind, not the title before it and not the file's name.
        assertThat(new TaskSession(context()).fromFiles(TASK, IDS)).contains("s-88");
    }

    @Test
    void aFileWithNoMatchingRecordFallsBackToItsName() {
        runner.answering("find", "/home/agent/.sessions/project/s-77.session\n");
        runner.answering("-n 50", "not a record\n{\"kind\":\"title\"}\n");

        assertThat(new TaskSession(context()).fromFiles(TASK, IDS)).contains("s-77");
    }

    @Test
    void noSessionFileMeansNoSession() {
        runner.answering("find", "");

        assertThat(new TaskSession(context()).fromFiles(TASK, IDS)).isEmpty();
    }

    @Test
    void continuesOnlyWhereTheAgentSaysHowAndSomethingWasRecorded() {
        final TaskSession sessions = new TaskSession(context());

        assertThat(sessions.toContinue(TASK, agent(true, IDS))).as("nothing recorded yet").isEmpty();
        sessions.record(TASK, "s-1");
        assertThat(sessions.toContinue(TASK, agent(true, IDS))).contains("s-1");
        // No declaration, no claim that anything continued: a fresh session, whatever is lying about.
        assertThat(sessions.toContinue(TASK, agent(false, null))).isEmpty();
        assertThat(sessions.toContinue(TASK, null)).isEmpty();
    }
}
