package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the agents {@link AgentDirectory} finds by a description under {@code agents.d}, under the vendor's own
 * names: no file of Sokar's name anywhere.
 */
class AgentDescriptionTest {

    @TempDir
    private Path root;

    private Path executable(final String name) throws IOException {
        final Path file = Files.createDirectories(root.resolve("opt/acme/bin")).resolve(name);
        Files.writeString(file, "#!/bin/sh\n");
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    private Path describe(final Path directory, final String name, final String executable) throws IOException {
        return Files.writeString(Files.createDirectories(directory).resolve(name + ".yaml"),
                "executable: " + executable + "\n");
    }

    private AgentDirectory directory() {
        return new AgentDirectory(List.of(root.resolve("own/agents"), root.resolve("libexec/agents")),
                List.of(root.resolve("own/agents.d"), root.resolve("share/agents.d")));
    }

    @Test
    void findsAnAgentByItsDescriptionUnderTheVendorsOwnNames() throws IOException {
        final Path coder = executable("coder");
        describe(root.resolve("share/agents.d"), "acme", coder.toString());

        assertThat(directory().executables()).containsExactly(coder);
        assertThat(directory().descriptions()).singleElement().satisfies(found -> {
            assertThat(found.name()).isEqualTo("acme");
            assertThat(found.executable()).isEqualTo(coder);
            assertThat(found.refusal()).isNull();
        });
    }

    @Test
    void refusesADescriptionThatNamesNoExecutableFileAndSaysWhy() throws IOException {
        final Path plain = Files.writeString(Files.createDirectories(root.resolve("opt")).resolve("plain"), "x");
        describe(root.resolve("share/agents.d"), "relative", "bin/coder");
        describe(root.resolve("share/agents.d"), "folder", root.resolve("opt").toString());
        describe(root.resolve("share/agents.d"), "plain", plain.toString());
        describe(root.resolve("share/agents.d"), "missing", root.resolve("nowhere").toString());

        assertThat(directory().executables()).as("no agent taken from any of them").isEmpty();
        assertThat(directory().descriptions()).extracting(AgentDirectory.Description::refusal)
                .allSatisfy(refusal -> assertThat(refusal).isNotNull())
                .anySatisfy(refusal -> assertThat(refusal).contains("absolute"))
                .anySatisfy(refusal -> assertThat(refusal).contains("not a regular file"))
                .anySatisfy(refusal -> assertThat(refusal).contains("not executable"));
    }

    @Test
    void theAccountsDescriptionHidesTheSystemsOfTheSameName() throws IOException {
        final Path packaged = executable("coder");
        final Path own = executable("coder-dev");
        final Path system = describe(root.resolve("share/agents.d"), "acme", packaged.toString());
        final Path mine = describe(root.resolve("own/agents.d"), "acme", own.toString());

        assertThat(directory().executables()).containsExactly(own);
        assertThat(directory().hiddenDescriptions()).containsEntry(system, mine);
    }

    @Test
    void removingTheDescriptionRemovesTheAgentThoughItsExecutableStays() throws IOException {
        final Path coder = executable("coder");
        final Path description = describe(root.resolve("share/agents.d"), "acme", coder.toString());
        assertThat(directory().executables()).containsExactly(coder);

        Files.delete(description);

        assertThat(directory().executables()).isEmpty();
        assertThat(coder).exists();
    }

    @Test
    void stillFindsAnAgentByItsSokarNameBesideTheDescriptions() throws IOException {
        // The agent packages already released install sokar-agent-<name>; they keep working.
        final Path old = Files.createDirectories(root.resolve("libexec/agents")).resolve(AgentDirectory.PREFIX + "x");
        Files.writeString(old, "#!/bin/sh\n");
        assertThat(old.toFile().setExecutable(true)).isTrue();
        final Path coder = executable("coder");
        describe(root.resolve("share/agents.d"), "acme", coder.toString());

        assertThat(directory().executables()).containsExactly(old, coder);
    }
}
