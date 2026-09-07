package org.fuin.sokar.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AgentDirectory}, and in particular for which copy of an agent runs.
 */
class AgentDirectoryTest {

    @TempDir
    private Path own;

    @TempDir
    private Path packaged;

    /**
     * Writes an executable agent binary.
     *
     * @param directory Where to write it.
     * @param name Agent name.
     * @return The file.
     * @throws IOException If it cannot be written.
     */
    private static Path agent(Path directory, String name) throws IOException {
        final Path file = Files.createFile(directory.resolve(AgentDirectory.PREFIX + name));
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
    }

    @Test
    void findsAnAgentInEitherLocation() throws IOException {

        final Path mine = agent(own, "alpha");
        final Path theirs = agent(packaged, "beta");

        assertThat(new AgentDirectory(List.of(own, packaged)).executables())
                .containsExactly(mine, theirs);
    }

    @Test
    void prefersTheOperatorsOwnCopy() throws IOException {

        final Path mine = agent(own, "alpha");
        agent(packaged, "alpha");

        assertThat(new AgentDirectory(List.of(own, packaged)).executables())
                .containsExactly(mine);
    }

    @Test
    void namesThePackagedCopyItHides() throws IOException {

        // Shadowing is silent, so a just-installed package looks like the one that runs.
        agent(own, "alpha");
        final Path theirs = agent(packaged, "alpha");

        assertThat(new AgentDirectory(List.of(own, packaged)).shadowed())
                .containsExactly(theirs);
    }

    @Test
    void namesWhatRunsInsteadOfTheCopyItHides() throws IOException {

        // "Not in use" is only half an answer. An operator looking at a binary that never runs
        // needs the one that does, or the next question is where to look - and the pairing has to
        // come from the class that implements the rule, or a caller matching them up would be a
        // second implementation of "which copy is in use".
        final Path mine = agent(own, "alpha");
        final Path theirs = agent(packaged, "alpha");

        assertThat(new AgentDirectory(List.of(own, packaged)).shadowedBy())
                .containsExactly(java.util.Map.entry(theirs, mine));
    }

    @Test
    void pairsNothingWhenNothingIsShadowed() throws IOException {

        agent(own, "alpha");
        agent(packaged, "beta");

        assertThat(new AgentDirectory(List.of(own, packaged)).shadowedBy()).isEmpty();
    }

    @Test
    void keepsTheOrderTheCopiesWereFoundIn() throws IOException {

        // The order is the only thing that explains why one wins, so it is not the map's to
        // discard - Map.copyOf would.
        agent(own, "alpha");
        agent(own, "beta");
        final Path hiddenAlpha = agent(packaged, "alpha");
        final Path hiddenBeta = agent(packaged, "beta");

        assertThat(new AgentDirectory(List.of(own, packaged)).shadowedBy().keySet())
                .containsExactly(hiddenAlpha, hiddenBeta);
    }

    @Test
    void reportsNothingShadowedWhenTheNamesDiffer() throws IOException {

        // A doctor that always claims something is shadowed teaches the operator to ignore it.
        agent(own, "alpha");
        agent(packaged, "beta");

        assertThat(new AgentDirectory(List.of(own, packaged)).shadowed()).isEmpty();
    }

    @Test
    void ignoresAFileThatIsNotExecutable() throws IOException {

        // A directory the operator can write to is not a place to run whatever is lying in it.
        Files.createFile(own.resolve(AgentDirectory.PREFIX + "alpha"));

        assertThat(new AgentDirectory(List.of(own)).executables()).isEmpty();
        assertThat(new AgentDirectory(List.of(own)).shadowed()).isEmpty();
    }

    @Test
    void survivesALocationThatDoesNotExist() throws IOException {

        final Path mine = agent(own, "alpha");

        assertThat(new AgentDirectory(List.of(own.resolve("absent"), own)).executables())
                .containsExactly(mine);
    }
}
