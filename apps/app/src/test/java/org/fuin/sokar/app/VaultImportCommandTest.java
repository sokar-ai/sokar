package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link VaultImportCommand}.
 */
class VaultImportCommandTest {

    @Test
    void expandsTheTildeADeclaredPathIsWrittenWith() {

        // Agents declare '~/.claude', because the definition is data and has no home directory.
        assertThat(VaultImportCommand.expand("~/.example"))
                .isEqualTo(Path.of(System.getProperty("user.home"), ".example"));
    }

    @Test
    void leavesAnAbsolutePathAlone() {

        // The negative case: mangling an absolute path would send the import somewhere else and
        // report success against the wrong file.
        assertThat(VaultImportCommand.expand("/etc/example")).isEqualTo(Path.of("/etc/example"));
    }
}
