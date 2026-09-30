package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.KernelKeyring;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for a transport that keeps a conversation of its own - a room - as Sokar sees it: without knowing
 * which transport it is.
 */
class TransportConversationsTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @TempDir
    Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private SokarContext context() throws IOException {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        // An adapter the directory finds: its name after the prefix is the scheme.
        final Path adapters = Files.createDirectories(dir.resolve("data/sokar/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static Project project(String securityClass) {
        return ProjectReader.read(new java.io.StringReader("""
                project:
                  name: "p"
                  security_class: "%s"
                image:
                  base_image: "ubuntu:24.04"
                mail:
                  transports:
                    room:
                      server: https://example.org
                  peers:
                    reviewer: { address: "room:", trust: vouched }
                """.formatted(securityClass)), "test");
    }

    private Mailbox mailbox(String container) throws IOException {
        final Mailbox mailbox = new Mailbox(dir.resolve("mail").resolve(container));
        mailbox.create();
        return mailbox;
    }

    private static void message(Path directory, String name, String to) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), "{\"metadata\":{\"to\":\"" + to + "\"},\"body\":\"hi\"}",
                StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(name + ".sig"), "sig", StandardCharsets.UTF_8);
    }

    @Test
    void whatArrivesGoesToTheTaskOfThatProjectItNamesAndNothingElseIsKept() throws IOException {
        final Mailbox review = mailbox("sokar-p-review");
        final Mailbox build = mailbox("sokar-p-build");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", "review");
        message(inbound, "$b.json", "sokar-p-build");
        message(inbound, "$c.json", "someone-on-another-machine");
        final Map<String, String> handed = new LinkedHashMap<>();

        final int dropped = TransportConversations.handOut(inbound, List.of(
                new TransportConversations.Member("sokar-p-review", review, "p", "room"),
                new TransportConversations.Member("sokar-p-build", build, "p", "room")), handed);

        assertThat(handed).containsEntry("$a.json", "sokar-p-review").containsEntry("$b.json", "sokar-p-build");
        assertThat(review.inbound().resolve("$a.json")).exists();
        assertThat(review.inbound().resolve("$a.json.sig")).as("with its signature").exists();
        assertThat(dropped).as("not this machine's").isEqualTo(1);
        try (var left = Files.list(inbound)) {
            assertThat(left).isEmpty();
        }
    }

    @Test
    void aMessageTheAgentTookIsMarkedReadOnceAndOnlyAMessageThatCameThroughTheConversation() throws IOException {
        final Mailbox review = mailbox("sokar-p-review");
        Files.writeString(review.record().resolve(TransportConversations.ARRIVED), "$a.json\n$b.json\n");
        // Taken by the agent: $a as a maildir reader names it, with flags; $b not yet; x never came that way.
        Files.writeString(review.inboxCur().resolve("$a.json:2,S"), "{}");
        Files.writeString(review.inboxNew().resolve("$b.json"), "{}");
        Files.writeString(review.inboxCur().resolve("x.json"), "{}");

        assertThat(TransportConversations.taken(review)).containsExactly("$a.json");
        Files.writeString(review.record().resolve(TransportConversations.MARKED), "$a.json\n");
        assertThat(TransportConversations.taken(review)).as("marked once").isEmpty();
        assertThat(TransportConversations.reference("$a.json")).isEqualTo("$a");
    }

    @Test
    void aPassPollsOncePerProjectWithTheProjectsSecretsAndMarksWhatWasTaken() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("describe", "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                    + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
            runner.answering("setup", "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                    + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            final Mailbox review = mailbox("sokar-p-review");

            assertThat(new TaskConversations(context).enroll(project("guarded"), "sokar-p-review")).isNull();
            Files.writeString(review.record().resolve(TransportConversations.ARRIVED), "$a.json\n");
            Files.writeString(review.inboxCur().resolve("$a.json"), "{}");
            final TransportConversations.Outcome outcome = new TransportConversations(context,
                    context.paths().transportDirectory()).pass(List.of(
                            new TransportConversations.Member("sokar-p-review", review, "p", "room")));

            assertThat(outcome.marked()).containsExactly("$a.json");
            final org.fuin.sokar.core.process.Command poll = runner.only(" poll ");
            assertThat(poll.environment()).as("the project's secrets, never the account's")
                    .containsEntry("POLLER", "p-1").doesNotContainKey("ADMIN");
            assertThat(runner.only(" read ").environment()).containsEntry("TASK", "t-1").doesNotContainKey("ADMIN");
            assertThat(runner.only(" enroll ").input()).as("the project's settings, verbatim")
                    .contains("\"server\":\"https://example.org\"");
            // Sending acts as the task, to the conversation setup named.
            assertThat(new TransportConversations(context, context.paths().transportDirectory())
                    .acting("room", "p", "sokar-p-review"))
                    .isEqualTo(new TransportSend.Acting(Map.of("TASK", "t-1"), "!room-p"));
        } finally {
            keyring.forget();
        }
    }

    @Test
    void anOfflineProjectsConversationMustNotReachBeyondLoopback() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("setup", "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                    + "\"reaches\":[\"matrix.example.org\"]}");

            assertThat(new TaskConversations(context).enroll(project("offline"), "sokar-p-review"))
                    .contains("an offline project's messages never leave this machine")
                    .contains("matrix.example.org");
            assertThat(runner.lines()).as("never enrolled").noneMatch(line -> line.contains(" enroll "));
        } finally {
            keyring.forget();
        }
    }

    @Test
    void loopbackIsLoopbackWithOrWithoutAPort() {
        assertThat(TaskConversations.loopback("127.0.0.1")).isTrue();
        assertThat(TaskConversations.loopback("127.0.0.1:18008")).isTrue();
        assertThat(TaskConversations.loopback("[::1]:18008")).isTrue();
        assertThat(TaskConversations.loopback("localhost")).isTrue();
        assertThat(TaskConversations.loopback("matrix.example.org")).isFalse();
        assertThat(TaskConversations.loopback("127.0.0.1.example.org")).isFalse();
    }
}
