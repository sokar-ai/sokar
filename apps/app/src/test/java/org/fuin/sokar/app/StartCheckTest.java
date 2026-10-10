package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link StartCheck}, for the outcomes reachable without a running agent.
 * <p>
 * The rest - the provider refusals and the two credential ones - are tested where their rule
 * lives, in {@code SelectedProviderTest} and {@code CredentialWiringTest}, and end to end against
 * a real agent in the acceptance suite. Restating them here against a stand-in agent would test a
 * stand-in.
 */
class StartCheckTest {

    private SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(),
                new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void saysSoWhenThereIsNoProjectFile(@TempDir Path dir) {

        // Checked first and cheaply: every other answer is about a run against a project, and
        // answering them for a project that is not there would be answering about nothing.
        final StartCheck.Result result =
                StartCheck.check(context(dir), dir.resolve("absent.yml"), null, null, null);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.NO_PROJECT_FILE);
        assertThat(result.ready()).isFalse();
        assertThat(result.detail()).contains("absent.yml");
    }

    @Test
    void answersWithoutAProjectFileWhenNoneIsGiven(@TempDir Path dir) {

        // The start dialog asks before a project is necessarily chosen, so the project is
        // optional and its absence is not the same as a missing file.
        assertThat(StartCheck.check(context(dir), null, null, null, null).outcome())
                .isEqualTo(StartCheck.Outcome.NO_AGENT);
    }

    @Test
    void saysNothingIsInstalledRatherThanFailing(@TempDir Path dir) throws Exception {

        // A machine with no agent is a normal machine, not a broken one - a task can still be
        // started as a shell - so this is an outcome and not an error.
        Files.createDirectories(dir.resolve("project"));
        final Path project = dir.resolve("project/project.yml");
        Files.writeString(project, "project:\n  name: \"uc\"\n  security_class: \"guarded\"\n");

        final StartCheck.Result result = StartCheck.check(context(dir), project, null, null, null);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.NO_AGENT);
        assertThat(result.detail()).contains("shell");
        assertThat(result.agent()).isEmpty();
    }

    @Test
    void namesTheAgentThatIsNotInstalled(@TempDir Path dir) {

        // The name somebody typed, back to them. "No agent is installed" when they asked for one
        // by name sends them looking at the machine rather than at what they typed.
        final StartCheck.Result result =
                StartCheck.check(context(dir), null, "not-installed", null, null);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.UNKNOWN_AGENT);
        assertThat(result.detail()).contains("not-installed");
    }

    @Test
    void readyIsTrueForExactlyOneOutcome(@TempDir Path dir) {

        // The bool and the outcome must not be able to disagree: a client is invited to branch on
        // whichever suits it, and two sources of one truth is how they come apart.
        for (final StartCheck.Outcome outcome : StartCheck.Outcome.values()) {
            final StartCheck.Result result = new StartCheck.Result(outcome, "", "", "", "");
            assertThat(result.ready())
                    .as("outcome %s", outcome)
                    .isEqualTo(outcome == StartCheck.Outcome.READY);
        }
    }

    @Test
    void everyAbsentValueTravelsAsAnEmptyStringNotAsNull(@TempDir Path dir) {

        // The same rule as the rest of this contract: "" and never the four characters "null",
        // which would render as a credential name somebody could go looking for.
        assertThat(StartCheck.check(context(dir), null, null, null, null).asMap())
                .containsEntry("agent", "").containsEntry("provider", "")
                .containsEntry("credential", "").containsEntry("ready", false)
                .containsKey("detail");
    }

    /** A project with two repositories beside its own. */
    private Path threeRepositories(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("project"));
        final Path project = dir.resolve("project/project.yml");
        Files.writeString(project, """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                repositories:
                  backend:
                  frontend:
                """);
        return project;
    }

    /**
     * What everything except the repository answers when nothing is in its way.
     * <p>
     * The names are deliberately not any agent's or provider's: nothing here depends on which they
     * are, and a real one written down outside the agent modules is what
     * {@code AgentIsolationTest} refuses - it caught this fixture.
     */
    private static final StartCheck.Result READY =
            new StartCheck.Result(StartCheck.Outcome.READY, "an-agent", "a-provider", "key", "");

    @Test
    void theRepositoryIsAskedLastSoARefusalNeverHidesTheOthers(@TempDir Path dir)
            throws Exception {

        // The point a question from the interface exposed: an interface asking "can work start in this
        // project at all" must not be told to choose a repository and learn nothing about a
        // missing agent. There is no agent on this machine, so that is the answer - even though no
        // repository was named either.
        assertThat(StartCheck.check(context(dir), threeRepositories(dir), null, null, null, null,
                null, true).outcome())
                .isEqualTo(StartCheck.Outcome.NO_AGENT);
    }

    @Test
    void sayingNothingAboutTheRepositoryIsRefusedWithTheChoices(@TempDir Path dir)
            throws Exception {

        // Asked of the last step directly, because reaching it through check() needs an agent
        // installed on the machine, which this test cannot do - and a fixture that could would be
        // testing a stand-in.
        final StartCheck.Result result =
                StartCheck.withRepository(READY, threeRepositories(dir), null, true);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.NO_REPOSITORY_CHOSEN);
        assertThat(result.ready()).isFalse();
        // Named, so a dialog can offer them without reading the file itself.
        assertThat(result.detail()).contains("uc").contains("backend").contains("frontend");
        // And what was already settled is carried, so the dialog does not lose it while asking.
        assertThat(result.agent()).isEqualTo("an-agent");
        assertThat(result.credential()).isEqualTo("key");
    }

    @Test
    void theOwnRepositoryOfAProjectThatNamesOthersIsNeverWorkedIn(@TempDir Path dir) throws Exception {

        // It holds project.yml, which decides what every task of the project may reach.
        final StartCheck.Result result = StartCheck.withRepository(READY, threeRepositories(dir), "uc", true);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.UNKNOWN_REPOSITORY);
        assertThat(result.detail()).contains("'uc' is the project's own repository")
                .contains("works in: backend, frontend");
    }

    @Test
    void theChoicesNeverIncludeTheOwnRepositoryOfAProjectThatNamesOthers(@TempDir Path dir) throws Exception {

        final StartCheck.Result result = StartCheck.withRepository(READY, threeRepositories(dir), null, true);

        assertThat(result.detail()).endsWith("works in: backend, frontend");
    }

    @Test
    void aProjectWithoutRepositoriesWorksInItsOwn(@TempDir Path dir) throws Exception {

        final Path file = dir.resolve("project.yml");
        java.nio.file.Files.writeString(file, """
                project:
                  name: "solo"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                """);

        assertThat(StartCheck.withRepository(READY, file, "solo", true)).isEqualTo(READY);
    }

    @Test
    void aRepositoryTheProjectDoesNotHaveIsItsOwnOutcome(@TempDir Path dir) throws Exception {

        // Different from naming none: somebody typed something, and what helps is the list.
        final StartCheck.Result result =
                StartCheck.withRepository(READY, threeRepositories(dir), "nowhere", true);

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.UNKNOWN_REPOSITORY);
        assertThat(result.detail()).contains("nowhere").contains("backend");
    }

    @Test
    void aRunWithNoGateIsNotAskedWhichRepository(@TempDir Path dir) throws Exception {

        // It gets an empty directory and can commit nowhere, so it works on no repository and
        // there is nothing for it to name.
        assertThat(StartCheck.withRepository(READY, threeRepositories(dir), null, false))
                .isEqualTo(READY);
    }

    @Test
    void aNamedRepositoryChangesNothingAboutTheAnswer(@TempDir Path dir) throws Exception {

        // Proves the refusals above are about the repository and not about the fixture: with a
        // good name, what everything else answered comes back untouched.
        assertThat(StartCheck.withRepository(READY, threeRepositories(dir), "backend", true))
                .isEqualTo(READY);
    }

    private Path credentialed(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("project"));
        final Path project = dir.resolve("project/project.yml");
        Files.writeString(project, """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                credentials:
                  search: nowhere
                """);
        return project;
    }

    @Test
    void anUndeclaredDestinationIsItsOwnOutcomeAndSaysWhoNamedIt(@TempDir Path dir) throws Exception {

        // Measured: the launch blamed the project for what the run had named, and an
        // interface could not tell the refusal from any other failed launch.
        final Path empty = threeRepositories(dir);
        final StartCheck.Result fromRun = StartCheck.withCredentials(context(dir), READY, empty,
                java.util.Map.of("f56-api", "nowhere"));
        assertThat(fromRun.outcome()).isEqualTo(StartCheck.Outcome.UNKNOWN_DESTINATION);
        assertThat(fromRun.credential()).isEqualTo("f56-api");
        assertThat(fromRun.detail()).startsWith("the run names credential 'f56-api' for 'nowhere'");

        final StartCheck.Result fromProject = StartCheck.withCredentials(context(dir), READY, credentialed(dir),
                java.util.Map.of());
        assertThat(fromProject.detail()).startsWith("the project names credential 'search' for 'nowhere'");
    }

    @Test
    void aRunPointingAProjectsCredentialElsewhereIsRefused(@TempDir Path dir) throws Exception {
        final StartCheck.Result result = StartCheck.withCredentials(context(dir), READY, credentialed(dir),
                java.util.Map.of("search", "somewhere-else"));

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.UNKNOWN_DESTINATION);
        assertThat(result.detail()).contains("cannot point one of the project's elsewhere");
    }

    @Test
    void aDeclaredDestinationLetsTheAnswerStand(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("data/sokar/destinations"));
        Files.writeString(dir.resolve("data/sokar/destinations/nowhere.yaml"),
                "name: nowhere\nupstream: https://api.example.com\n");

        assertThat(StartCheck.withCredentials(context(dir), READY, credentialed(dir), java.util.Map.of()))
                .isEqualTo(READY);
    }

    @Test
    void anUndeclaredDestinationIsAnsweredBeforeAMissingCredential(@TempDir Path dir) throws Exception {

        // Measured: told only that the agent's credential was missing, an interface offered a
        // shell, and Start then refused it for the destination - which refuses in every mode.
        final StartCheck.Result missing = new StartCheck.Result(StartCheck.Outcome.CREDENTIAL_MISSING, "asker", "p",
                "anthropic", "the vault holds no credential for 'anthropic'");

        assertThat(StartCheck.withCredentials(context(dir), missing, credentialed(dir), java.util.Map.of())
                .outcome()).isEqualTo(StartCheck.Outcome.UNKNOWN_DESTINATION);
        final StartCheck.Result declared = StartCheck.withCredentials(context(dir), missing, threeRepositories(dir),
                java.util.Map.of());
        assertThat(declared).as("nothing else in the way: the missing credential stands").isEqualTo(missing);
    }

    @Test
    void aCredentialNobodyGrantedIsItsOwnOutcomeWithTheCommandThatGrantsIt(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.fuin.sokar.vault.KernelKeyring.available(),
                "libkeyutils is not installed");
        final SokarContext context = context(dir);
        Files.createDirectories(dir.resolve("data/sokar/destinations"));
        Files.writeString(dir.resolve("data/sokar/destinations/nowhere.yaml"),
                "name: nowhere\nupstream: https://api.example.com\n");
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final org.fuin.sokar.vault.VaultEntry service = new org.fuin.sokar.vault.VaultEntry("-", "oauth-device",
                java.util.Map.of("client_id", "x", "device_authorization_url", "https://a/d", "token_url", "https://a/t"));
        final org.fuin.sokar.vault.KernelKeyring keyring =
                new org.fuin.sokar.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(java.util.Map.of("search", service), passphrase);
            keyring.store(passphrase);

            final StartCheck.Result needed = StartCheck.withCredentials(context, READY, credentialed(dir),
                    java.util.Map.of());
            assertThat(needed.outcome()).isEqualTo(StartCheck.Outcome.AUTHORIZATION_NEEDED);
            assertThat(needed.credential()).isEqualTo("search");
            assertThat(needed.detail()).contains("sokar vault authorize search");

            context.vault().write(java.util.Map.of("search", service, TaskSecrets.GRANT_PREFIX + "search",
                    new org.fuin.sokar.vault.VaultEntry("rt-1", "refresh-token", java.util.Map.of())), passphrase);
            assertThat(StartCheck.withCredentials(context, READY, credentialed(dir), java.util.Map.of()))
                    .as("granted: nothing in the way").isEqualTo(READY);
        } finally {
            keyring.forget();
        }
    }

    private Path upstreams(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("project"));
        final Path project = dir.resolve("project/project.yml");
        Files.writeString(project, """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                repositories:
                  backend:
                    upstream: "git@forge.example.org:uc/backend.git"
                  web:
                    upstream: "https://forge.example.org/uc/web.git"
                """);
        return project;
    }

    @Test
    void aRepositoryAtAHostThisMachineNeverMetIsRefusedBeforeAnythingIsMade(@TempDir Path dir) throws Exception {

        // The start went ahead, ssh refused the workspace's fetch, and the task ran without a gate; the only trace
        // was a line of prose in its output (found on 2026-10-02).
        final SokarContext context = context(dir);

        final StartCheck.Result result = StartCheck.withHost(context, READY, upstreams(dir), "backend");

        assertThat(result.outcome()).isEqualTo(StartCheck.Outcome.UNKNOWN_HOST_KEY);
        assertThat(result.host()).isEqualTo("forge.example.org");
        assertThat(result.detail()).contains("trust-host forge.example.org");
        assertThat(result.asMap()).containsEntry("host", "forge.example.org");
    }

    @Test
    void aHostThisMachineRemembersOrOneOverHttpsIsNoQuestion(@TempDir Path dir) throws Exception {
        final SokarContext context = context(dir);
        final Path project = upstreams(dir);

        assertThat(StartCheck.withHost(context, READY, project, "web")).as("https is TLS's").isEqualTo(READY);

        final Path known = FollowCredential.knownHostsFile(context);
        Files.createDirectories(known.getParent());
        Files.writeString(known, "forge.example.org ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample\n");
        assertThat(StartCheck.withHost(context, READY, project, "backend")).isEqualTo(READY);
    }

    @Test
    void anUnknownHostIsSaidBeforeALockedVaultBecauseTrustingItNeedsNone(@TempDir Path dir) throws Exception {

        // Asked only once all else was ready, it hid behind a locked vault, and a person unlocked first only to be
        // asked about the host next (found on 2026-10-02).
        final StartCheck.Result locked =
                new StartCheck.Result(StartCheck.Outcome.VAULT_LOCKED, "an-agent", "a-provider", "key", "locked");

        assertThat(StartCheck.withHost(context(dir), locked, upstreams(dir), "backend").outcome())
                .isEqualTo(StartCheck.Outcome.UNKNOWN_HOST_KEY);
    }

    @Test
    void aHostIsKnownOnlyByItsOwnEntryAndPortAndAUserlessAddressIsSshToo(@TempDir Path dir) throws Exception {

        // A line for '[forge.example.org.evil]:22' counted for forge.example.org, a port-22 line for an upstream on
        // another port, and 'forge:org/repo.git' was not taken for ssh at all - each fell back to a start without a
        // gate.
        final SokarContext context = context(dir);
        final Path known = FollowCredential.knownHostsFile(context);
        Files.createDirectories(known.getParent());
        Files.writeString(known, "[forge.example.org.evil]:22 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample\n"
                + "other.example.org ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample\n");

        assertThat(StartCheck.unknownHostOf(context, "git@forge.example.org:o/r.git")).isEqualTo("forge.example.org");
        assertThat(StartCheck.unknownHostOf(context, "ssh://git@other.example.org:2222/o/r.git"))
                .isEqualTo("other.example.org");
        assertThat(StartCheck.unknownHostOf(context, "forge.example.org:o/r.git")).isEqualTo("forge.example.org");
        assertThat(StartCheck.unknownHostOf(context, "git@other.example.org:o/r.git")).isNull();
    }
}
