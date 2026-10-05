package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.vault.KernelKeyring;
import org.fuin.sokar.vault.VaultEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link VaultClearing} and {@code sokar vault clear}: the vault, its backup, its lock and what the
 * keyring caches of it go in one step, after the transports have cleared what they keep with its secrets.
 * <p>
 * Every path is in a temporary directory, and the keyring descriptions derive from the vault's path there, so
 * nothing here touches the developer's own vault or keyring; whatever a test caches is forgotten after it.
 */
class VaultClearingTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @TempDir
    Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private @Nullable SokarContext used;

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        final SokarContext context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        used = context;
        return context;
    }

    @AfterEach
    void forgetWhatTheKeyringHolds() {
        // Every test's own description, from its own temporary vault: a passphrase left behind outlives the test in
        // the developer's account.
        if (used != null && KernelKeyring.available()) {
            new KernelKeyring(used.paths().vault().vaultKeyringKey()).forget();
            VaultShare.forget(used.paths());
        }
    }

    /** A vault holding a credential, a grant, a transport's account and a stopped task's token, unlocked. */
    private SokarContext unlockedVault() throws IOException {
        assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");
        final SokarContext context = context();
        Files.createDirectories(context.vault().path().getParent());
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        entries.put("github", VaultEntry.of("not-a-real-token"));
        entries.put(VaultNames.GRANT_PREFIX + "mail", VaultEntry.of("not-a-real-refresh-token"));
        entries.put(VaultNames.TRANSPORT_PREFIX + "room/account", VaultEntry.of("{\"ADMIN\":\"a-1\"}"));
        entries.put(VaultNames.TASK_PREFIX + "sokar-p-old/gate-token", VaultEntry.of("g-1"));
        context.vault().write(entries, PASSPHRASE);
        new KernelKeyring(context.paths().vault().vaultKeyringKey()).store(PASSPHRASE);
        return context;
    }

    private Path backup(final SokarContext context) {
        return context.vault().path().resolveSibling(context.vault().path().getFileName() + ".old");
    }

    private Path lock(final SokarContext context) {
        return context.vault().path().resolveSibling(context.vault().path().getFileName() + ".lock");
    }

    /** A project registered on this machine, from a file of the person's. */
    private void project(final String name, final String extra) throws IOException {
        Files.createDirectories(dir.resolve("data/sokar/projects"));
        final Path file = dir.resolve(name + "-project.yml");
        Files.writeString(file, "project:\n  name: \"" + name + "\"\n  security_class: \"guarded\"\n"
                + "image:\n  base_image: \"ubuntu:24.04\"\n" + extra, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("data/sokar/projects").resolve(name), file + "\n", StandardCharsets.UTF_8);
    }

    /** An adapter the transport directory finds, answering what the runner is told to answer. */
    private void transport(final String scheme) throws IOException {
        final Path adapters = Files.createDirectories(dir.resolve("data/sokar/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + scheme);
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering("describe", "{\"scheme\":\"" + scheme + "\",\"lifecycle\":[\"setup\",\"enroll\",\"retire\","
                + "\"clear\"]}");
    }

    private int execute(final SokarContext context, final String... arguments) {
        final CommandLine command = SokarCli.commandLine(context);
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        return command.execute(arguments);
    }

    private static boolean names(final Clearing.Result result, final String kind, final String what,
            final Clearing.Status status) {
        return result.items().stream().anyMatch(item -> item.kind().equals(kind) && item.what().contains(what)
                && item.status() == status);
    }

    @Test
    void aListingNamesTheFilesTheKeyringAndWhatTheVaultHoldsAndRemovesNothing() throws IOException {
        final SokarContext context = unlockedVault();
        Files.writeString(backup(context), "an older copy");
        Files.writeString(lock(context), "");

        final Clearing.Result listed = new VaultClearing(context).clear(true, false);

        assertThat(listed.refused()).isFalse();
        assertThat(names(listed, "vault", context.vault().path().toString(), Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "backup", backup(context).toString(), Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "lock", lock(context).toString(), Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "keyring", "passphrase", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "credential", "github", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "grant", "mail", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "transport account", "room/account", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "task token", "sokar-p-old", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(context.vault().path()).exists();
        assertThat(backup(context)).exists();
        assertThat(lock(context)).exists();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read()).as("still cached").isPresent();
    }

    @Test
    void clearingRemovesTheFilesAndForgetsThePassphraseAndEveryDevicesShare() throws IOException {
        final SokarContext context = unlockedVault();
        Files.writeString(backup(context), "an older copy");
        Files.writeString(lock(context), "");
        VaultShare.keep(context.paths(), new byte[] { 1, 2, 3 }, Duration.ofMinutes(5));

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(cleared.refused()).isFalse();
        assertThat(cleared.items()).noneMatch(item -> item.status() == Clearing.Status.NOT_REMOVED);
        assertThat(context.vault().path()).doesNotExist();
        assertThat(backup(context)).doesNotExist();
        assertThat(lock(context)).doesNotExist();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read()).isEmpty();
        assertThat(VaultShare.held(context.paths())).isEmpty();
        assertThat(new VaultClearing(context).clear(false, false).items()).as("nothing left to clear").isEmpty();
    }

    @Test
    void afterAClearInitStartsAsOnANewMachineAndTheOldPassphraseIsNotCached() throws IOException {
        final SokarContext context = unlockedVault();

        assertThat(execute(context, "vault", "clear", "--yes")).as("said: %s%s", out, err).isZero();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read()).isEmpty();

        final SokarContext initializing = new SokarContext(new org.fuin.sokar.core.process.ProcessCommandRunner(),
                context.paths(), arguments -> 0);
        assertThat(execute(initializing, "vault", "init", "--passphrase-command", "printf fresh"))
                .as("said: %s%s", out, err).isZero();
        assertThat(context.vault().read("fresh".toCharArray())).isEmpty();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read())
                .hasValueSatisfying(cached -> assertThat(new String(cached)).isEqualTo("fresh"));
    }

    @Test
    void withoutYesTheCommandListsAndSaysNothingWasRemoved() throws IOException {
        final SokarContext context = unlockedVault();

        assertThat(execute(context, "vault", "clear")).as("said: %s%s", out, err).isZero();

        assertThat(out.toString()).contains("would go").contains(context.vault().path().toString())
                .contains("--yes");
        assertThat(context.vault().path()).exists();
    }

    @Test
    void aRunningTaskThatHoldsATokenFromTheVaultIsNamedAndTheClearRefusedEvenWithForce() throws IOException {
        final SokarContext context = unlockedVault();
        final Map<String, VaultEntry> entries = new LinkedHashMap<>(context.vault().read(PASSPHRASE));
        entries.put(VaultNames.TASK_PREFIX + "sokar-p-shell-1/provider-token", VaultEntry.of("p-1"));
        context.vault().write(entries, PASSPHRASE);
        runner.answering("ps", "sokar-p-shell-1\tUp 4 minutes\n");

        final Clearing.Result cleared = new VaultClearing(context).clear(false, true);

        assertThat(cleared.refused()).isTrue();
        assertThat(names(cleared, "task", "sokar-p-shell-1", Clearing.Status.NOT_REMOVED)).isTrue();
        assertThat(context.vault().path()).exists();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read()).isPresent();
    }

    @Test
    void aRunningTaskThatHoldsNothingFromTheVaultDoesNotStopTheClear() throws IOException {
        final SokarContext context = unlockedVault();
        runner.answering("ps", "sokar-p-shell-2\tUp 4 minutes\n");

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(cleared.refused()).isFalse();
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void aProjectsAccountOnAServerElsewhereIsClearedWithTheVaultsSecretsBeforeItGoesAndTheAccountAfter()
            throws IOException {
        final SokarContext context = unlockedVault();
        transport("room");
        project("p", "mail:\n  transports:\n    room:\n      server: https://example.org\n");
        project("q", "");
        final Map<String, VaultEntry> entries = new LinkedHashMap<>(context.vault().read(PASSPHRASE));
        entries.put(VaultNames.TRANSPORT_PREFIX + "room/project/p", VaultEntry.of("{\"POLLER\":\"p-1\"}"));
        context.vault().write(entries, PASSPHRASE);
        runner.answering(" clear", "{\"removed\":[\"the room\"],\"kept\":[]}");

        final Clearing.Result listed = new VaultClearing(context).clear(true, false);
        assertThat(names(listed, "conversation", "p", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "transport", "room", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(runner.lines()).noneMatch(line -> line.contains(" clear"));

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(cleared.refused()).isFalse();
        final List<String> lines = runner.lines();
        final int project = indexOf(lines, line -> line.contains(" clear --project p"));
        final int account = indexOf(lines, line -> line.endsWith(" clear"));
        assertThat(project).as("the project's clear ran: %s", lines).isNotNegative();
        assertThat(account).as("the account's clear ran after it: %s", lines).isGreaterThan(project);
        assertThat(lines).as("a project with no server elsewhere is the account's to clear")
                .noneMatch(line -> line.contains(" clear --project q"));
        final Command projectClear = runner.only("clear --project p");
        assertThat(projectClear.environment()).as("it was given the vault's secrets, so the vault was still there")
                .containsEntry("POLLER", "p-1").containsEntry("ADMIN", "a-1");
        assertThat(runner.invocations().stream().filter(each -> each.describe().endsWith(" clear")).findFirst()
                .orElseThrow().environment()).as("the account's clear runs after the vault went")
                .doesNotContainKey("ADMIN");
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void aTransportThatCannotClearAProjectKeepsTheVaultUnlessForced() throws IOException {
        final SokarContext context = unlockedVault();
        transport("room");
        project("p", "mail:\n  transports:\n    room:\n      server: https://example.org\n");
        runner.failing("clear --project", 75, "the server did not answer");

        final Clearing.Result refused = new VaultClearing(context).clear(false, false);

        assertThat(refused.refused()).isTrue();
        assertThat(refused.items()).anyMatch(item -> item.status() == Clearing.Status.NOT_REMOVED
                && item.why().contains("the server did not answer"));
        assertThat(context.vault().path()).exists();
        assertThat(runner.lines()).noneMatch(line -> line.endsWith(" clear"));

        final Clearing.Result forced = new VaultClearing(context).clear(false, true);

        assertThat(forced.refused()).isFalse();
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void aLockedVaultIsClearedOnlyWithForceBecauseWhatItHoldsCannotBeNamed() throws IOException {
        final SokarContext context = context();
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of("github", VaultEntry.of("not-a-real-token")), PASSPHRASE);

        final Clearing.Result refused = new VaultClearing(context).clear(false, false);

        assertThat(refused.refused()).isTrue();
        assertThat(refused.items()).anyMatch(item -> item.why().contains("locked"));
        assertThat(context.vault().path()).exists();

        final Clearing.Result forced = new VaultClearing(context).clear(false, true);

        assertThat(forced.refused()).isFalse();
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void aProjectsDeployKeysAreNamedForAPersonToRemoveAtTheForge() throws IOException {
        final SokarContext context = unlockedVault();
        project("web", "repositories:\n  api:\n    upstream: \"git@forge.example.org:o/web.git\"\n");
        final Project web = GateSupport.byName(context, "web");
        final DeployKeys.Key made;
        try {
            made = DeployKeys.make(context, web, "api", null, false);
        } catch (final DeployKeys.Refused ex) {
            throw new AssertionError(ex);
        }

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(names(cleared, "deploy key", made.fingerprint(), Clearing.Status.FOR_A_PERSON)).isTrue();
        assertThat(cleared.keys()).extracting(DeployKeys.Key::fingerprint).containsExactly(made.fingerprint());
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void anAccountWithNoVaultHasNothingToClear() {
        assertThat(new VaultClearing(context()).clear(false, false).items()).isEmpty();
    }

    private static int indexOf(final List<String> lines, final java.util.function.Predicate<String> which) {
        for (int i = 0; i < lines.size(); i++) {
            if (which.test(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
