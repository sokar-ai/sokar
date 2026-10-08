package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandRunner;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectException;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.core.project.SecurityClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the project {@code default}: work on a repository no followed project names, with Sokar's settings.
 */
class DefaultProjectTest {

    @TempDir
    Path dir;

    private final CommandRunner runner = new ProcessCommandRunner();

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @Test
    void aRepositoryIsAddedOnceNamedAfterItsAddressAndTheSameRepositoryAgainIsTheSameEntry() throws Exception {
        final DefaultProject project = new DefaultProject(context());

        final DefaultProject.Entry added = project.add("git@github.com:you/Web-App.git", null, "/home/you/web-app");

        assertThat(added.name()).isEqualTo("web-app");
        assertThat(project.add("https://github.com/you/Web-App", null, "")).as("the same repository, spelt"
                + " differently").isEqualTo(added);
        assertThat(project.add("git@gitlab.com:other/web-app.git", null, "").name()).as("a name already taken")
                .isEqualTo("web-app-2");
        assertThat(project.entries()).hasSize(2);
    }

    @Test
    void aNameThatIsTakenOrIsTheProjectsOwnIsRefused() throws Exception {
        final DefaultProject project = new DefaultProject(context());
        project.add("git@github.com:you/app.git", "app", "");

        assertThatThrownBy(() -> project.add("git@github.com:you/other.git", "app", ""))
                .isInstanceOf(DefaultProject.Refused.class).hasMessageContaining("already");
        assertThatThrownBy(() -> project.add("git@github.com:you/other.git", "default", ""))
                .isInstanceOf(DefaultProject.Refused.class).hasMessageContaining("not a repository name");
    }

    @Test
    void itsFileIsSokarsSettingsWithItsRepositoriesAndAnEditToItDoesNotLast() throws Exception {
        final DefaultProject project = new DefaultProject(context());
        project.add("git@github.com:you/app.git", null, "");
        final Path file = project.file();
        Files.writeString(file, Files.readString(file).replace("guarded", "online"));

        final Project read = ProjectReader.read(project.file());

        assertThat(read.name()).isEqualTo(DefaultProject.NAME);
        assertThat(read.securityClass()).isEqualTo(SecurityClass.GUARDED);
        assertThat(read.egress().sets()).isEmpty();
        assertThat(read.egress().domains()).isEmpty();
        assertThat(read.repositories()).singleElement()
                .satisfies(repository -> assertThat(repository.upstream()).isEqualTo("git@github.com:you/app.git"));
    }

    @Test
    void theNameIsReservedAndTheProjectIsNeverRemovedNorALeftOver() throws Exception {
        final SokarContext context = context();

        assertThat(ProjectSource.resolve(context, DefaultProject.NAME).outcome())
                .isEqualTo(ProjectSource.Outcome.BUILT_IN);
        assertThatThrownBy(() -> new FollowedProjects(context.paths().projects().followed())
                .follow(DefaultProject.NAME, "git@github.com:you/project.git", false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no followed project");
        assertThat(new ProjectDeletion(context).delete(DefaultProject.NAME, false, true).outcome())
                .isEqualTo(ProjectDeletion.Outcome.BUILT_IN);
        assertThat(ProjectFileCheck.check(context, "project:\n  name: \"default\"\n  security_class: \"guarded\"\n"
                + "image:\n  base_image: \"ubuntu:24.04\"\n").refused()).singleElement().asString()
                .contains("no followed project");
        assertThat(new ProjectInventory(context).projects()).anySatisfy(summary -> {
            assertThat(summary.name()).isEqualTo(DefaultProject.NAME);
            assertThat(summary.repositories()).as("no phantom repository of its own").isEmpty();
            assertThat(Prune.orphaned(summary, java.util.Set.of())).isFalse();
        });
    }

    @Test
    void aCheckoutClonedWithATokenGoesIntoDefaultWithoutIt() throws Exception {

        // Cloning with a token leaves it in the origin. Kept as it was, it was written into what 'default' lists and
        // printed in what the start said: a secret in a file and in scrollback.
        final SokarContext context = context();
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        git(checkout, "init", "-q");
        git(checkout, "remote", "add", "origin", "https://dev:ghp_secret123@github.com/you/app.git");

        final WorkOrigin.Choice choice = WorkOrigin.fromCheckout(context, checkout);

        assertThat(new DefaultProject(context).entries()).singleElement()
                .satisfies(entry -> assertThat(entry.remote()).isEqualTo("https://github.com/you/app.git"));
        assertThat(choice.said()).doesNotContain("ghp_secret123").doesNotContain("dev:")
                .contains("carried a credential");
        // Everything Sokar wrote; the checkout's own git configuration is the developer's and holds it still.
        try (var files = Files.walk(dir)) {
            for (final Path file : files.filter(Files::isRegularFile).filter(file -> !file.startsWith(checkout)).toList()) {
                assertThat(Files.readString(file)).as(file.toString()).doesNotContain("ghp_secret123");
            }
        }
        // The user of an ssh address is not a secret, and stays.
        assertThat(RepositoryAddress.withoutCredential("git@github.com:you/app.git")).isEqualTo("git@github.com:you/app.git");
    }

    @Test
    void aCheckoutNoFollowedProjectNamesGoesIntoDefault() throws Exception {
        final SokarContext context = context();
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        git(checkout, "init", "-q");
        git(checkout, "remote", "add", "origin", "git@github.com:you/app.git");

        final WorkOrigin.Choice choice = WorkOrigin.fromCheckout(context, checkout);

        assertThat(choice.project()).isEqualTo(DefaultProject.NAME);
        assertThat(choice.repository()).isEqualTo("app");
        // The checkout is the source both ways: its history goes in, its work comes back to it; its remote is the
        // person's own to pull and push, and is kept only to be shown.
        assertThat(new DefaultProject(context).entries()).singleElement().satisfies(entry -> {
            assertThat(entry.checkout()).isEqualTo(checkout.toRealPath().toString());
            assertThat(entry.upstream()).isEqualTo(checkout.toRealPath().toString());
            assertThat(entry.source()).isEqualTo(DefaultProject.Source.CHECKOUT);
            assertThat(entry.remote()).isEqualTo("git@github.com:you/app.git");
        });
        assertThat(choice.said()).contains(checkout.toRealPath().toString()).contains("sokar/<task>");
    }

    @Test
    void twoCheckoutsOfOneRemoteAreTwoRepositoriesEachItsOwnSource() throws Exception {
        final SokarContext context = context();
        final Path first = Files.createDirectories(dir.resolve("app"));
        final Path second = Files.createDirectories(dir.resolve("other/app"));
        for (final Path checkout : java.util.List.of(first, second)) {
            git(checkout, "init", "-q");
            git(checkout, "remote", "add", "origin", "git@github.com:you/app.git");
        }

        assertThat(WorkOrigin.fromCheckout(context, first).repository()).isEqualTo("app");
        assertThat(WorkOrigin.fromCheckout(context, second).repository()).isEqualTo("app-2");
        assertThat(WorkOrigin.fromCheckout(context, first).repository()).as("the same checkout again").isEqualTo("app");
        assertThat(new DefaultProject(context).entries()).extracting(DefaultProject.Entry::upstream)
                .containsExactly(first.toRealPath().toString(), second.toRealPath().toString());
    }

    @Test
    void anEntryAddedByAddressIsItsRemotesAsEveryEntryFromBeforeIs() throws Exception {
        final DefaultProject added = new DefaultProject(context());
        added.add("git@github.com:you/lib.git", null, "");

        assertThat(added.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.source()).isEqualTo(DefaultProject.Source.REMOTE);
            assertThat(entry.remote()).isEqualTo("git@github.com:you/lib.git");
        });
    }

    @Test
    void aCheckoutWithNoOriginIsItsOwnSourceAndNoCheckoutAtAllIsRefused() throws Exception {
        final SokarContext context = context();
        final Path checkout = Files.createDirectories(dir.resolve("loose"));
        git(checkout, "init", "-q");

        // Its work comes back to it, so it needs no remote at all.
        assertThat(WorkOrigin.fromCheckout(context, checkout).repository()).isEqualTo("loose");
        assertThat(new DefaultProject(context).entries()).singleElement()
                .satisfies(entry -> assertThat(entry.remote()).isEmpty());
        assertThatThrownBy(() -> WorkOrigin.fromCheckout(context, Files.createDirectories(dir.resolve("plain"))))
                .isInstanceOf(ProjectException.class).hasMessageContaining("in no git checkout");
    }

    @Test
    void aRepositoryTakenOutForgetsThisMachinesDeployKeyForItAndSaysWhichToRemoveAtTheForge() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(org.fuin.sokar.vault.KernelKeyring.available(),
                "libkeyutils is not installed");
        final SokarContext context = context();
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final org.fuin.sokar.vault.KernelKeyring keyring =
                new org.fuin.sokar.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(java.util.Map.of(), passphrase);
            keyring.store(passphrase);
            final DefaultProject project = new DefaultProject(context);
            project.add("git@github.com:you/app.git", null, "");
            final DeployKeys.Key made = DeployKeys.make(context, ProjectReader.read(project.file()), "app", null, false);
            assertThat(made.writeAccess()).as("a work repository: approved work is pushed there").isTrue();

            assertThat(project.remove("app")).singleElement()
                    .satisfies(key -> assertThat(key.fingerprint()).isEqualTo(made.fingerprint()));

            assertThat(context.vault().read(passphrase)).doesNotContainKey(made.entry());
            assertThat(project.remove("app")).as("no longer there").isNull();
        } finally {
            keyring.forget();
        }
    }

    @Test
    void oneRepositoryIsRecognisedWhicheverOfGitsSpellingsNamesIt() {
        assertThat(RepositoryAddress.same("git@github.com:you/app.git", "ssh://git@github.com/you/app")).isTrue();
        assertThat(RepositoryAddress.same("https://GitHub.com/you/app/", "git@github.com:you/app.git")).isTrue();
        assertThat(RepositoryAddress.same("git@github.com:you/app.git", "git@github.com:you/other.git")).isFalse();
        assertThat(RepositoryAddress.same("/srv/git/app.git", "/srv/git/app")).isTrue();
        assertThat(RepositoryAddress.same(null, "git@github.com:you/app.git")).isFalse();
    }

    private void git(Path where, String... arguments) {
        final java.util.List<String> all = new java.util.ArrayList<>(java.util.List.of("git", "-C", where.toString()));
        all.addAll(java.util.List.of(arguments));
        assertThat(runner.run(Command.of(all)).successful()).as(String.join(" ", all)).isTrue();
    }

    @Test
    void twoWritersAtOnceLoseNoRepositoryAndNeitherFails() throws Exception {

        // Measured on the VM: two processes wrote default's project file through one staged name, and the second found
        // it gone - "the project file of 'default' cannot be written: .project.yml.new -> project.yml".
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            final java.util.List<java.util.concurrent.Future<?>> done = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int n = i;
                done.add(pool.submit(() -> {
                    // A machine of its own each, as two processes have.
                    final DefaultProject own = new DefaultProject(context());
                    own.add("git@github.com:you/repo-" + n + ".git", null, "");
                    return own.file();
                }));
            }
            for (final java.util.concurrent.Future<?> each : done) {
                each.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(new DefaultProject(context()).entries()).hasSize(16);
    }
}
