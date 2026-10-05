package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TaskState} and {@link TaskSecrets}: what of a task a reboot must not take, and that no secret
 * is kept outside the vault.
 */
class TaskStateTest {

    private static final String TASK = "sokar-p-t";

    private static final String GATE_TOKEN = "gate-secret-0123456789";

    @TempDir
    Path dir;

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private Path runningTask(SokarContext context) throws IOException {
        final Path runtime = Files.createDirectories(context.paths().tasks().containerState(TASK));
        Files.writeString(runtime.resolve("task.json"), "{\"version\":3}");
        Files.writeString(runtime.resolve("ruleset.nft"), "table inet sokar {}");
        Files.writeString(runtime.resolve("dnsmasq.servers"), "server=/example.com/1.1.1.1\n");
        Files.writeString(runtime.resolve("vault.token"), "sokar_pt_phantom");
        Files.writeString(runtime.resolve("gate.pid"), "4242");
        new TaskHelpers(List.of(
                new TaskHelpers.Helper("vault", List.of("sokar", "vault", "serve"), Map.of(), TaskHelpers.BEFORE),
                new TaskHelpers.Helper("gate", List.of("sokar", "gate", "serve"),
                        Map.of(TaskState.GATE_TOKEN, GATE_TOKEN, "SOKAR_GATE_UPSTREAM", "https://up.example/r.git"),
                        TaskHelpers.AFTER))).writeTo(runtime);
        return runtime;
    }

    @Test
    void savesWhatIsKnowledgeAboutTheTaskAndNothingLiveOrSecret() throws IOException {
        final SokarContext context = context();
        runningTask(context);

        new TaskState(context).save(TASK);

        final Path durable = context.paths().tasks().taskRecord(TASK);
        assertThat(durable).startsWith(dir.resolve("state"));
        assertThat(durable.resolve("task.json")).exists();
        assertThat(durable.resolve("ruleset.nft")).hasContent("table inet sokar {}");
        assertThat(durable.resolve("dnsmasq.servers")).exists();
        // Live, or secret: not saved.
        assertThat(durable.resolve("gate.pid")).doesNotExist();
        assertThat(durable.resolve("vault.token")).doesNotExist();
        // The resume record without the gate token, and with everything else.
        assertThat(Files.readString(durable.resolve(TaskHelpers.FILE))).doesNotContain(GATE_TOKEN)
                .contains("SOKAR_GATE_UPSTREAM");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(durable))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(durable.resolve("task.json"))))
                .isEqualTo("rw-------");
    }

    @Test
    void aFileThatIsGoneIsGoneFromTheSavedCopyToo() throws IOException {
        final SokarContext context = context();
        final Path runtime = runningTask(context);
        Files.writeString(runtime.resolve("granted"), "example.org\n");
        new TaskState(context).save(TASK);
        Files.delete(runtime.resolve("granted"));

        new TaskState(context).save(TASK);

        assertThat(context.paths().tasks().taskRecord(TASK).resolve("granted")).doesNotExist();
    }

    @Test
    void whatARebootEmptiedIsPutBackAndTheGateIsGivenItsTokenAgain() throws IOException {
        final SokarContext context = context();
        final Path runtime = runningTask(context);
        final TaskState state = new TaskState(context);
        state.save(TASK);
        deleteTree(runtime);

        assertThat(state.saved(TASK)).isTrue();
        state.restore(TASK);

        assertThat(runtime.resolve("ruleset.nft")).hasContent("table inet sokar {}");
        final TaskHelpers restored = TaskControl.withGateToken(TaskHelpers.readFrom(runtime), GATE_TOKEN);
        assertThat(restored.helpers()).filteredOn(helper -> "gate".equals(helper.name())).singleElement()
                .satisfies(gate -> assertThat(gate.environment()).containsEntry(TaskState.GATE_TOKEN, GATE_TOKEN)
                        .containsEntry("SOKAR_GATE_UPSTREAM", "https://up.example/r.git"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(runtime))).isEqualTo("rwx------");
    }

    @Test
    void aTaskNothingWasSavedForCannotBeBroughtBack() {
        assertThat(new TaskState(context()).saved(TASK)).isFalse();
    }

    @Test
    void removingTheTaskForgetsWhatWasSaved() throws IOException {
        final SokarContext context = context();
        runningTask(context);
        new TaskState(context).save(TASK);

        new TaskState(context).forget(TASK);

        assertThat(context.paths().tasks().taskRecord(TASK)).doesNotExist();
    }

    @Test
    void theTokensAreReadWhereTheLaunchLeftThem() throws IOException {
        final SokarContext context = context();
        final Path runtime = runningTask(context);

        final TaskSecrets.Tokens tokens = TaskSecrets.fromRuntime(runtime);

        assertThat(tokens.gate()).isEqualTo(GATE_TOKEN);
        assertThat(tokens.provider()).isEqualTo("sokar_pt_phantom");
    }

    @Test
    void aTasksOwnEntriesAreNoCredentialAnyListingSees() {
        final Map<String, VaultEntry> entries = Map.of(
                "anthropic", VaultEntry.of("sk-real"),
                "task/sokar-p-t/gate-token", VaultEntry.of(GATE_TOKEN),
                "task/sokar-p-t/provider-token", VaultEntry.of("sokar_pt_phantom"));

        assertThat(TaskSecrets.credentialsOnly(entries)).containsOnlyKeys("anthropic");
        assertThat(TaskSecrets.reserved("task/x/gate-token")).isTrue();
        assertThat(TaskSecrets.reserved("tasks")).isFalse();
    }

    @Test
    void withNoVaultAtAllNothingWasKeptWhichIsNotTheSameAsLocked() {
        assertThat(new TaskSecrets(context()).read(TASK)).contains(new TaskSecrets.Tokens(null, null));
    }

    @Test
    void keepingATasksTokensPrunesGoneTasksOnlyAndNeverTheAccountsGrantsOrTransportSecrets() throws IOException {

        // Found by Agent Frontend: every task start pruned every reserved name whose "owner" was no running
        // container - and a grant or a transport's secrets are owned by no task, so they went every time.
        org.junit.jupiter.api.Assumptions.assumeTrue(org.fuin.sokar.vault.KernelKeyring.available(),
                "libkeyutils is not installed");
        final SokarContext context = context();
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final org.fuin.sokar.vault.KernelKeyring keyring =
                new org.fuin.sokar.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(
                    TaskSecrets.GRANT_PREFIX + "forge-app", org.fuin.sokar.vault.VaultEntry.of("rt-1"),
                    TaskSecrets.TRANSPORT_PREFIX + "matrix/account", org.fuin.sokar.vault.VaultEntry.of("{}"),
                    TaskSecrets.PREFIX + "sokar-p-gone/gate-token", org.fuin.sokar.vault.VaultEntry.of("old"),
                    TaskSecrets.PREFIX + "sokar-p-other/route/forge", org.fuin.sokar.vault.VaultEntry.of("rt-2"),
                    "anthropic", org.fuin.sokar.vault.VaultEntry.of("sk-1")), passphrase);
            keyring.store(passphrase);

            assertThat(new TaskSecrets(context).keep(TASK, new TaskSecrets.Tokens(GATE_TOKEN, null),
                    java.util.Set.of(TASK, "sokar-p-other"))).isEmpty();

            assertThat(context.vault().read(passphrase)).containsKeys(TaskSecrets.GRANT_PREFIX + "forge-app",
                    TaskSecrets.TRANSPORT_PREFIX + "matrix/account", "anthropic")
                    // A route token is task/<container>/route/<name>: its owner was read up to the last slash,
                    // '<container>/route', which no task is called - so every start dropped every other task's.
                    .as("another task's route token").containsKey(TaskSecrets.PREFIX + "sokar-p-other/route/forge")
                    .as("a task that is gone").doesNotContainKey(TaskSecrets.PREFIX + "sokar-p-gone/gate-token");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aLockedVaultKeepsNothingAndSaysTheTaskCannotComeBack() {
        final String said = new TaskSecrets(context()).keep(TASK, new TaskSecrets.Tokens(GATE_TOKEN, null),
                java.util.Set.of(TASK));

        assertThat(said).contains("cannot come back after a reboot");
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
