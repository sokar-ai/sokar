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
    void whatArrivesGoesToEveryTaskOfThatProjectWhomeverItNamesAndNothingIsKept() throws IOException {

        // Every agent reads every message in the room (2026-10-04); metadata.to says whom it is meant for.
        final Mailbox review = mailbox("sokar-p-review");
        final Mailbox build = mailbox("sokar-p-build");
        final Path inbound = Files.createDirectories(dir.resolve("inbound"));
        message(inbound, "$a.json", "review");
        message(inbound, "$c.json", "someone-on-another-machine");
        final Map<String, String> handed = new LinkedHashMap<>();
        final List<String> said = new java.util.ArrayList<>();

        final int dropped = TransportConversations.handOut(inbound, List.of(
                new TransportConversations.Member("sokar-p-review", review, "p", "room"),
                new TransportConversations.Member("sokar-p-build", build, "p", "room")), handed, said);

        assertThat(handed).containsEntry("$a.json", "sokar-p-review, sokar-p-build")
                .containsEntry("$c.json", "sokar-p-review, sokar-p-build");
        assertThat(review.inbound().resolve("$a.json")).exists();
        assertThat(build.inbound().resolve("$a.json.sig")).as("with its signature").exists();
        assertThat(dropped).isZero();
        assertThat(said).isEmpty();
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
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
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
            // Set up again at the next start: given back what it printed, the account's and the project's.
            assertThat(new TaskConversations(context).enroll(project("guarded"), "sokar-p-other")).isNull();
            assertThat(runner.invocations().stream().filter(command -> command.describe().contains(" setup "))
                    .toList().getLast().environment()).containsEntry("ADMIN", "a-1").containsEntry("POLLER", "p-1");
            Files.writeString(review.record().resolve(TransportConversations.ARRIVED), "$a.json\n");
            Files.writeString(review.inboxCur().resolve("$a.json"), "{}");
            final TransportConversations.Outcome outcome = new TransportConversations(context,
                    context.paths().messaging().transportDirectory()).pass(List.of(
                            new TransportConversations.Member("sokar-p-review", review, "p", "room")));

            assertThat(outcome.marked()).containsExactly("$a.json");
            final org.fuin.sokar.core.process.Command poll = runner.only(" poll ");
            assertThat(poll.environment()).as("the project's secrets, never the account's")
                    .containsEntry("POLLER", "p-1").doesNotContainKey("ADMIN");
            assertThat(runner.only(" read ").environment()).containsEntry("TASK", "t-1").doesNotContainKey("ADMIN");
            assertThat(runner.only("--task sokar-p-review").input()).as("the project's settings, verbatim")
                    .contains("\"server\":\"https://example.org\"");
            // Sending acts as the task, to the conversation setup named.
            assertThat(new TransportConversations(context, context.paths().messaging().transportDirectory())
                    .acting("room", "p", "sokar-p-review"))
                    .isEqualTo(new TransportSend.Acting(Map.of("TASK", "t-1"), "!room-p"));
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aReadTheHomeserverRefusesIsReportedAndNeverRecordedAsMarkedWhileATemporaryOneIsAskedAgain() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        // 77 is the homeserver refusing - a rejected token, an account in no room that holds the event - and was
        // recorded as marked, so the sender's receipt said its message was read when nobody had confirmed it.
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("describe", "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                    + "\"lifecycle\":[\"setup\",\"enroll\"]}");
            runner.answering("setup", "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                    + "\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.failing(" read $refused", 77, "M_FORBIDDEN: the token was rejected");
            runner.failing(" read $later", TransportSend.TEMPORARY, "the homeserver is busy");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "sokar-p-review")).isNull();
            final Mailbox review = mailbox("sokar-p-review");
            Files.writeString(review.record().resolve(TransportConversations.ARRIVED), "$refused.json\n$later.json\n");
            Files.writeString(review.inboxCur().resolve("$refused.json"), "{}");
            Files.writeString(review.inboxCur().resolve("$later.json"), "{}");
            final TransportConversations conversations =
                    new TransportConversations(context, context.paths().messaging().transportDirectory());
            final List<TransportConversations.Member> members = List.of(
                    new TransportConversations.Member("sokar-p-review", review, "p", "room"));

            final TransportConversations.Outcome first = conversations.pass(members);
            final TransportConversations.Outcome second = conversations.pass(members);

            assertThat(first.marked()).as("refused is not done").isEmpty();
            assertThat(second.marked()).isEmpty();
            assertThat(review.record().resolve(TransportConversations.MARKED)).doesNotExist();
            assertThat(first.failures()).singleElement().asString().contains("refused")
                    .contains("exit 77").contains("M_FORBIDDEN");
            assertThat(runner.lines().stream().filter(line -> line.contains(" read $refused")))
                    .as("a refusal is final, asking again every pass changes nothing").hasSize(1);
            assertThat(runner.lines().stream().filter(line -> line.contains(" read $later")))
                    .as("only a temporary failure is asked again").hasSize(2);
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aTransportThatTakesTheMachinesNameIsGivenItAndOneThatDoesNotIsNot() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("describe", "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],"
                    + "\"takes\":[\"machine\"]}");
            runner.answering("setup", "{\"secrets\":{},\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");

            assertThat(new TaskConversations(context).enroll(project("guarded"), "sokar-p-review")).isNull();

            // Two machines on one homeserver run tasks of the same name; the machine's name keeps them apart.
            assertThat(runner.only(" setup ").arguments()).containsSequence("--machine", DeployKeys.hostName());
            assertThat(runner.only(" enroll ").arguments()).containsSequence("--machine", DeployKeys.hostName());
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aMachineNotLetIntoASharedConversationKeepsItsAccountAndStartsNoTaskUntilItIs() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("describe", "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],"
                    + "\"takes\":[\"machine\"]}");
            runner.answering("setup", "{\"account\":{\"MACHINE\":\"m-1\"},\"machine\":\"host-b\","
                    + "\"admitted\":false,\"address\":\"@sokar.host-b:example.org\",\"room\":\"#p:example.org\"}");

            final String refused = new TaskConversations(context).enroll(project("guarded"), "sokar-p-review");

            assertThat(refused).as("whom to let in, and where").contains("@sokar.host-b:example.org")
                    .contains("#p:example.org");
            assertThat(runner.lines()).as("no task is enrolled while the machine is not in")
                    .noneMatch(each -> each.contains(" enroll "));
            assertThat(new TransportLifecycle(context, context.paths().messaging().transportDirectory()).secrets("room", "account"))
                    .as("the account it registered is kept, not lost with a refusal")
                    .containsEntry("MACHINE", "m-1");
            assertThat(Conversations.row(context, project("guarded")))
                    .containsEntry("machine", "host-b").containsEntry("admitted", false)
                    .containsEntry("address", "@sokar.host-b:example.org").containsEntry("room", "#p:example.org")
                    .containsEntry("ready", false);
        } finally {
            keyring.forget();
        }
    }

    @Test
    void whatATaskSentIsAskedAboutByTheRecipientsAddressAndRecordedReadOnce() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("describe", "{\"scheme\":\"room\",\"poll\":true,\"confirms\":\"read\","
                    + "\"lifecycle\":[\"setup\",\"enroll\"]}");
            runner.answering("setup", "{\"secrets\":{\"POLLER\":\"p-1\"},\"conversation\":\"!r\","
                    + "\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("--task sokar-p-write", "{\"secrets\":{\"TASK\":\"w-1\"},\"address\":\"@write\"}");
            runner.answering("--task sokar-p-review", "{\"secrets\":{\"TASK\":\"r-1\"},\"address\":\"@review\"}");
            runner.answering(" receipt ", "{\"state\":\"read\",\"at\":\"2026-09-30T08:00:00Z\"}");
            final TaskConversations tasks = new TaskConversations(context);
            assertThat(tasks.enroll(project("guarded"), "sokar-p-write")).isNull();
            assertThat(tasks.enroll(project("guarded"), "sokar-p-review")).isNull();
            final Mailbox write = mailbox("sokar-p-write");
            Files.writeString(write.sent().resolve("m-1.json"),
                    "{\"messageId\":\"m-1\",\"metadata\":{\"to\":\"review\"}}");
            Files.writeString(write.sent().resolve("m-1.json.receipt.json"), "{\"reference\":\"$e1\"}");
            final TransportConversations conversations =
                    new TransportConversations(context, context.paths().messaging().transportDirectory());
            final List<TransportConversations.Member> members = List.of(
                    new TransportConversations.Member("sokar-p-write", write, "p", "room"));

            conversations.pass(members);
            conversations.pass(members);

            final org.fuin.sokar.core.process.Command asked = runner.only(" receipt ");
            assertThat(asked.arguments()).endsWith("receipt", "$e1", "--by", "@review");
            assertThat(asked.environment()).as("as the sender").containsEntry("TASK", "w-1");
            assertThat(Files.readString(write.record().resolve(MessageRecord.FILE)))
                    .contains("\"event\":\"read\"").contains("\"peer\":\"review\"");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void anAddressIsATasksShortOrFullNameOrAPersons() {
        final Map<String, String> tasks = Map.of("sokar-p-review", "@review");
        final Map<String, String> people = Map.of("michi", "@michi");
        assertThat(TransportConversations.addressOf("review", "p", tasks, people)).isEqualTo("@review");
        assertThat(TransportConversations.addressOf("sokar-p-review", "p", tasks, people)).isEqualTo("@review");
        assertThat(TransportConversations.addressOf("michi", "p", tasks, people)).isEqualTo("@michi");
        assertThat(TransportConversations.addressOf("nobody", "p", tasks, people)).isNull();
    }

    @Test
    void anOfflineProjectsConversationMustNotReachBeyondLoopback() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
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
            assertThat(runner.only(" setup ").arguments()).as("told before it reaches anything")
                    .endsWith("--loopback-only");
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

    @Test
    void aTaskWhoseAccountCannotBeReadHereIsNotSentAsNobody() throws Exception {

        // Its conversation is there, its account is not readable - a locked vault, or a daemon that sees another one:
        // send ran without it, and the message came back held with "SOKAR_MATRIX_HOMESERVER is not set".
        final SokarContext context = context();
        final Path state = Files.createDirectories(context.paths().xdg().state().resolve("transport/room"));
        Files.writeString(state.resolve("p.json"), "{\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");

        final TransportConversations conversations =
                new TransportConversations(context, context.paths().messaging().transportDirectory());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> conversations.acting("room", "p", "sokar-p-review"))
                .isInstanceOf(IOException.class).hasMessageContaining("no account");
        // A project with no conversation in it asks for nothing beyond the message, as before.
        assertThat(conversations.acting("room", "other", "sokar-other-review")).isNull();
    }

    @Test
    void clearingAProjectRunsTheTransportsClearAndForgetsWhatSokarKeptForItThenTheAccountsToo() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        // With them kept, a task started again after the transport had cleared ran no setup and stayed outside a
        // conversation that was gone (measured by sokar-message-matrix, 2026-10-02).
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("describe", "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"clear\"]}");
            runner.answering("setup", "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                    + "\"conversation\":\"!r\",\"reaches\":[\"127.0.0.1\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.answering(" clear", "{\"removed\":[\"the room\"],\"kept\":[]}");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "sokar-p-review")).isNull();
            final TransportLifecycle lifecycle = new TransportLifecycle(context, context.paths().messaging().transportDirectory());

            final List<String> removed = lifecycle.clear("room", "p", Map.of());

            assertThat(runner.lines()).anyMatch(line -> line.contains(" clear --project p"));
            assertThat(removed).contains("the room");
            assertThat(lifecycle.secrets("room", "project/p")).isEmpty();
            assertThat(lifecycle.secrets("room", "task/sokar-p-review")).isEmpty();
            assertThat(lifecycle.conversation("room", "p")).as("set up anew at the next start").isNull();
            assertThat(lifecycle.secrets("room", "account")).as("the account outlives one project").isNotEmpty();

            lifecycle.clear("room", null, Map.of());

            assertThat(runner.lines()).anyMatch(line -> line.endsWith(" clear"));
            assertThat(lifecycle.secrets("room", "account")).isEmpty();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aWaitingPollAsksForPersonsAndHoldsTheServerOpenAndAPassThenLeavesFetchingToIt() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("describe", "{\"scheme\":\"room\",\"poll\":true,\"persons\":true,\"waits\":true,"
                    + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
            runner.answering("setup", "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                    + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            final Mailbox review = mailbox("sokar-p-review");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "sokar-p-review")).isNull();
            final List<TransportConversations.Member> group = List.of(
                    new TransportConversations.Member("sokar-p-review", review, "p", "room"));
            final TransportConversations conversations =
                    new TransportConversations(context, context.paths().messaging().transportDirectory());

            conversations.waitOnce(group);

            assertThat(runner.only(" poll ").arguments()).contains("--persons").containsSubsequence("--wait", "30");
            TransportConversations.WAITING.add(TransportConversations.key("room", "p"));
            try {
                conversations.pass(group);
                assertThat(runner.invocations().stream().filter(command -> command.describe().contains(" poll "))
                        .count()).as("no second poll beside the waiting one").isEqualTo(1);
            } finally {
                TransportConversations.WAITING.remove(TransportConversations.key("room", "p"));
            }
        } finally {
            keyring.forget();
        }
    }

    @Test
    void whatAPollSaysOnItsErrorIsSaidEvenWhenItSucceeds() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            runner.answering("describe", "{\"scheme\":\"room\",\"poll\":true,"
                    + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\",\"join\"]}");
            runner.answering("setup", "{\"account\":{\"ADMIN\":\"a-1\"},\"secrets\":{\"POLLER\":\"p-1\"},"
                    + "\"conversation\":\"!room-p\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering("enroll", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.answering(" poll ", "", "skipped 1 encrypted message from @michi:localhost");
            final Mailbox review = mailbox("sokar-p-review");
            assertThat(new TaskConversations(context).enroll(project("guarded"), "sokar-p-review")).isNull();

            final TransportConversations.Outcome outcome = new TransportConversations(context,
                    context.paths().messaging().transportDirectory()).pass(List.of(
                            new TransportConversations.Member("sokar-p-review", review, "p", "room")));

            assertThat(outcome.failures()).anySatisfy(said -> assertThat(said).contains("encrypted message"));
        } finally {
            keyring.forget();
        }
    }
}
