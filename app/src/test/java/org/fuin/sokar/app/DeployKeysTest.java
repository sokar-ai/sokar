package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.process.ProcessCommandRunner;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.ProjectReader;
import org.fuin.sokar.vault.KernelKeyring;
import org.fuin.sokar.vault.VaultEntry;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link DeployKeys}: each repository of each project has its own key, whatever the names share.
 */
class DeployKeysTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @TempDir
    Path dir;

    private SokarContext context() {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new ProcessCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static Project project(String name, String repository, String upstream) {
        return ProjectReader.read(new StringReader("""
                project: { name: "%s", security_class: "guarded" }
                image: { base_image: "ubuntu:24.04" }
                repositories:
                  %s:
                    upstream: "%s"
                """.formatted(name, repository, upstream)), "test");
    }

    @Test
    void projectsWhoseNamesMeetKeepKeysOfTheirOwnAndClearingOneLeavesTheOther() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        // 'web' with 'app-api' and 'web-app' with 'api' were both 'deploy-web-app-api': the second was given the
        // first one's key, and clearing 'web' deleted 'web-app''s.
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            final Project web = project("web", "app-api", "git@forge.example.org:o/one.git");
            final Project webApp = project("web-app", "api", "git@forge.example.org:o/two.git");

            final DeployKeys.Key first = DeployKeys.make(context, web, "app-api", null, false);
            final DeployKeys.Key second = DeployKeys.make(context, webApp, "api", null, false);

            assertThat(second.entry()).isNotEqualTo(first.entry());
            assertThat(second.fingerprint()).isNotEqualTo(first.fingerprint());
            DeployKeys.forget(context, web);
            assertThat(context.vault().read(PASSPHRASE)).containsKey(second.entry()).doesNotContainKey(first.entry());
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aKeyKeptUnderTheOldNameIsTakenOnlyByTheRepositoryItWasMadeFor() throws Exception {
        Assumptions.assumeTrue(KernelKeyring.available(), "libkeyutils is not installed");

        // Machines hold keys under the old name; the one that registered it at a forge keeps it, and a project whose
        // names only meet it does not inherit it.
        final SokarContext context = context();
        final KernelKeyring keyring = new KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), PASSPHRASE);
            keyring.store(PASSPHRASE);
            final Project webApp = project("web-app", "api", "git@forge.example.org:o/two.git");
            final DeployKeys.Key made = DeployKeys.make(context, webApp, "api", null, false);
            final Map<String, VaultEntry> all = new java.util.LinkedHashMap<>(context.vault().read(PASSPHRASE));
            all.put("deploy-web-app-api", all.remove(made.entry()));
            context.vault().write(all, PASSPHRASE);

            assertThat(DeployKeys.make(context, project("web", "app-api", "git@forge.example.org:o/one.git"),
                    "app-api", null, false).fingerprint()).isNotEqualTo(made.fingerprint());
            assertThat(DeployKeys.make(context, webApp, "api", null, false).fingerprint())
                    .isEqualTo(made.fingerprint());
        } finally {
            keyring.forget();
        }
    }
}
