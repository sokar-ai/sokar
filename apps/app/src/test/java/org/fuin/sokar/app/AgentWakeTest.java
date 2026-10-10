package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.agent.api.AgentDefinition;
import org.fuin.sokar.agent.api.AgentDefinitionReader;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link AgentWake}.
 */
class AgentWakeTest {

    @TempDir
    private Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static final AgentDefinition CLAUDE = AgentDefinitionReader.read(new StringReader("""
            name: claude
            binary: claude
            git_identity: { name: C, email: c@example.com }
            headless: { prompt_flag: "-p" }
            session:
              at_rest:
                shows: ["bypass permissions on"]
                lacks: ["esc to interrupt", "Esc to cancel"]
              waiting:
                screen:
                  - { contains: "Esc to cancel", for: "a choice" }
            """), "claude.yaml");

    private boolean typed() {
        return runner.invocations().stream().anyMatch(command -> command.arguments().contains("send-keys"));
    }

    @Test
    void anAgentAtRestIsWokenWithSokarsOwnLine() {
        runner.answering("capture-pane", ">\n  bypass permissions on (shift+tab)\n");

        assertThat(new AgentWake(context()).wake("sokar-p-writer", CLAUDE)).isTrue();

        assertThat(runner.invocations()).anySatisfy(command -> assertThat(command.arguments())
                .contains("-l", AgentWake.LINE));
    }

    @Test
    void anAgentAtWorkIsNotInterrupted() {
        runner.answering("capture-pane", "* Thinking (esc to interrupt)\n  bypass permissions on\n");

        assertThat(new AgentWake(context()).wake("sokar-p-writer", CLAUDE)).isFalse();
        assertThat(typed()).isFalse();
    }

    @Test
    void aQuestionOpenForAPersonIsNeverAnswered() {
        runner.answering("capture-pane", "Do you want to proceed?\n 1. Yes\n Enter to select · Esc to cancel\n"
                + "  bypass permissions on\n");

        assertThat(new AgentWake(context()).wake("sokar-p-writer", CLAUDE)).isFalse();
        assertThat(typed()).isFalse();
    }

    @Test
    void anAgentThatSaysNothingAboutRestIsNeverTypedInto() {
        final AgentDefinition silent = AgentDefinitionReader.read(new StringReader("""
                name: quiet
                binary: quiet
                git_identity: { name: Q, email: q@example.com }
                headless: { prompt_flag: "-p" }
                """), "quiet.yaml");

        assertThat(new AgentWake(context()).wake("sokar-p-writer", silent)).isFalse();
        assertThat(runner.invocations()).isEmpty();
    }

    @Test
    void anAgentThatCannotSayWhenAQuestionIsOpenIsNeverTypedIntoEvenAtRest() {

        // The pi agent shows nothing that tells a question it put from its rest, so a line typed into
        // it could land as the answer to a question nobody can see is open.
        final AgentDefinition restOnly = AgentDefinitionReader.read(new StringReader("""
                name: pi
                binary: pi
                git_identity: { name: P, email: p@example.com }
                headless: { prompt_flag: "-p" }
                session:
                  at_rest:
                    shows: ["(auto)"]
                """), "pi.yaml");
        runner.answering("capture-pane", "What is your favourite colour?\n/workspace (master)\n (auto)\n");

        assertThat(new AgentWake(context()).wake("sokar-p-writer", restOnly)).isFalse();
        assertThat(typed()).isFalse();
    }

    @Test
    void onlyAMessageThatNamesTheTaskOrIsSaidToItDirectlyWakesIt() throws IOException {
        final Path named = Files.writeString(dir.resolve("a.json"), "{\"metadata\":{\"to\":[\"writer\"]}}");
        final Path other = Files.writeString(dir.resolve("b.json"), "{\"metadata\":{\"to\":[\"review\"]}}");
        final Path direct = Files.writeString(dir.resolve("c.json"), "{\"metadata\":{\"to\":[],\"direct\":true}}");
        final Path single = Files.writeString(dir.resolve("d.json"), "{\"metadata\":{\"to\":\"writer\"}}");

        assertThat(AgentWake.namesIt(named, "sokar-p-writer", "p")).isTrue();
        assertThat(AgentWake.namesIt(other, "sokar-p-writer", "p")).as("the room's talk").isFalse();
        assertThat(AgentWake.namesIt(direct, "sokar-p-writer", "p")).isTrue();
        assertThat(AgentWake.namesIt(single, "sokar-p-writer", "p")).as("a task's message to it").isTrue();
    }

    @Test
    void aPersonsWordInTheRoomToNobodyWakesEveryTaskAndATasksToNobodyWakesNone() throws IOException {
        final Path everyone = Files.writeString(dir.resolve("e.json"),
                "{\"metadata\":{\"from\":\"michael\",\"to\":[],\"via\":\"room\"}}");
        final Path another = Files.writeString(dir.resolve("f.json"),
                "{\"metadata\":{\"from\":\"michael\",\"to\":[\"review\"],\"via\":\"room\"}}");
        final Path aTasks = Files.writeString(dir.resolve("g.json"), "{\"metadata\":{\"from\":\"review\",\"to\":[]}}");

        assertThat(AgentWake.namesIt(everyone, "sokar-p-writer", "p")).as("'Wer ist da?' in the room").isTrue();
        assertThat(AgentWake.namesIt(another, "sokar-p-writer", "p")).as("a person's word to another").isFalse();
        assertThat(AgentWake.namesIt(aTasks, "sokar-p-writer", "p")).as("a task's word to nobody").isFalse();
    }

    @Test
    void aFilterNoteWakesOnlyWhenItRefusedOrWouldHave() throws IOException {
        final Path refused = Files.writeString(dir.resolve("h.json"),
                "{\"metadata\":{\"inReplyToMessageId\":\"m-1\"},\"parts\":[{\"data\":{\"decision\":\"rejected\"}}]}");
        final Path flagged = Files.writeString(dir.resolve("i.json"), "{\"metadata\":{\"inReplyToMessageId\":\"m-2\"},"
                + "\"parts\":[{\"data\":{\"decision\":\"approved\",\"wouldReject\":true}}]}");
        final Path receipt = Files.writeString(dir.resolve("j.json"), "{\"metadata\":{\"inReplyToMessageId\":\"m-3\"},"
                + "\"parts\":[{\"data\":{\"decision\":\"approved\",\"wouldReject\":false}}]}");

        assertThat(AgentWake.namesIt(refused, "sokar-p-writer", "p")).isTrue();
        assertThat(AgentWake.namesIt(flagged, "sokar-p-writer", "p")).isTrue();
        assertThat(AgentWake.namesIt(receipt, "sokar-p-writer", "p")).as("a plain receipt").isFalse();
        // An id the detectors flagged is not repeated in the answer (2026-10-04).
        final Path unlinked = Files.writeString(dir.resolve("k.json"),
                "{\"metadata\":{},\"parts\":[{\"data\":{\"decision\":\"rejected\"}}]}");
        assertThat(AgentWake.namesIt(unlinked, "sokar-p-writer", "p")).as("a refusal of a flagged id").isTrue();
    }

    @Test
    void aMessageThatFoundTheAgentAtWorkIsAnnouncedOnceItRestsAndOnlyOnce() throws IOException {

        // 2026-10-04: a word that arrived while the agent worked was never announced, and it went to rest
        // with mail it was never told of.
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("sokar-p-writer"));
        mailbox.create();
        Files.writeString(mailbox.inboxNew().resolve("m-1.json"), "{\"metadata\":{\"via\":\"direct\"}}");
        final Path state = dir.resolve("run").resolve("sokar").resolve("sokar-p-writer");
        final SokarContext context = context();
        Files.createDirectories(context.paths().tasks().containerState("sokar-p-writer"));
        final AgentWake waker = new AgentWake(context);
        runner.answering("capture-pane", "* Working (esc to interrupt)\n  bypass permissions on\n");

        assertThat(waker.announce(mailbox, "sokar-p-writer", "p", () -> waker.wake("sokar-p-writer", CLAUDE))).as("at work: not now").isFalse();

        runner.answering("capture-pane", ">\n  bypass permissions on\n");
        assertThat(waker.announce(mailbox, "sokar-p-writer", "p", () -> waker.wake("sokar-p-writer", CLAUDE))).as("at rest at the next pass").isTrue();
        assertThat(waker.announce(mailbox, "sokar-p-writer", "p", () -> waker.wake("sokar-p-writer", CLAUDE))).as("told once").isFalse();
        assertThat(runner.invocations().stream().filter(command -> command.arguments().contains("-l")).count())
                .isEqualTo(1);
        assertThat(state).isNotNull();
    }

    @Test
    void aFileThatArrivedIsAnnouncedOnceByNameAgainWhenItChangesAndNotWhileTheAgentWorks() throws Exception {

        // An agent pushed and never learned that its build's verdict had arrived.
        final SokarContext context = context();
        final String task = "sokar-p-writer";
        Files.createDirectories(context.paths().tasks().containerState(task));
        org.fuin.sokar.wire.Sidecar.recordId(context.paths().tasks().containerState(task), "0123456789ab");
        final HandIns handIns = new HandIns(context.paths().tasks(), context.podman(), (arguments, stdin) -> 0,
                java.time.Clock.systemUTC());
        final java.util.List<String> lines = new java.util.ArrayList<>();
        final boolean[] atRest = {false};
        final AgentWake waker = new AgentWake(context);
        final java.util.function.Predicate<String> typing = line -> {
            if (atRest[0]) {
                lines.add(line);
            }
            return atRest[0];
        };

        handIns.give(task, "build-48c52ae4f6b1.txt", Files.writeString(dir.resolve("v1"), "verdict: running\n"),
                HandIns.SOKAR);
        assertThat(waker.announceFiles(task, typing)).as("at work: not now").isFalse();

        atRest[0] = true;
        assertThat(waker.announceFiles(task, typing)).as("at rest at the next pass").isTrue();
        assertThat(waker.announceFiles(task, typing)).as("told once").isFalse();
        // Past the quiet after a line: a task without a mailbox keeps it beside its record.
        Files.delete(context.paths().tasks().taskRecord(task).resolve(AgentWake.LAST));

        handIns.give(task, "build-48c52ae4f6b1.txt", Files.writeString(dir.resolve("v2"), "verdict: failure\n"),
                HandIns.SOKAR);
        assertThat(waker.announceFiles(task, typing)).as("the verdict changed").isTrue();

        assertThat(lines).containsExactly(AgentWake.filesLine(java.util.List.of("build-48c52ae4f6b1.txt")),
                AgentWake.filesLine(java.util.List.of("build-48c52ae4f6b1.txt")));
        assertThat(lines.get(0)).isEqualTo("A file arrived in /sokar/files: build-48c52ae4f6b1.txt - read "
                + TaskGuide.GUIDE + " if you do not know what it is for.");
        assertThat(AgentWake.filesLine(java.util.stream.IntStream.range(0, 7).mapToObj(n -> "f" + n).toList()))
                .startsWith("Files arrived in /sokar/files: f0, f1, f2, f3, f4 and 2 more");
    }

    @Test
    void twoPathsAtTheSameMomentOfRestTypeOneLineAndAnotherArrivalWaitsOutTheQuiet() throws Exception {

        // 2026-10-04: the same message announced twice, and the second Enter broke the agent's turn.
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve("sokar-p-writer"));
        mailbox.create();
        Files.writeString(mailbox.inboxNew().resolve("m-1.json"), "{\"metadata\":{\"via\":\"direct\"}}");
        final AgentWake waker = new AgentWake(context());
        final java.util.concurrent.atomic.AtomicInteger typed = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.function.BooleanSupplier atRest = () -> {
            typed.incrementAndGet();
            return true;
        };

        final java.util.List<Thread> paths = java.util.stream.IntStream.range(0, 4)
                .mapToObj(n -> Thread.ofPlatform().start(() -> waker.announce(mailbox, "sokar-p-writer", "p", atRest)))
                .toList();
        for (final Thread path : paths) {
            path.join(java.time.Duration.ofSeconds(10));
        }
        Files.writeString(mailbox.inboxNew().resolve("m-2.json"), "{\"metadata\":{\"via\":\"direct\"}}");

        assertThat(waker.announce(mailbox, "sokar-p-writer", "p", atRest)).as("within the quiet").isFalse();
        assertThat(typed.get()).isEqualTo(1);
    }
}
