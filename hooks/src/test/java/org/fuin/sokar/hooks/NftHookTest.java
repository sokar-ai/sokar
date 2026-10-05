package org.fuin.sokar.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link NftHook}: a stage it does not know is a container that would start without its firewall.
 */
class NftHookTest {

    private int execute(final Path dir, final String... arguments) throws Exception {
        final Path sidecar = dir.resolve("sidecar.json");
        new Sidecar(Sidecar.VERSION, "uc", "guarded", dir.resolve("ruleset.nft").toString(),
                dir.resolve("dns.conf").toString(), "/usr/bin/sokar", dir.toString()).writeTo(sidecar);
        final String state = "{\"ociVersion\":\"1.0.2\",\"id\":\"abc123\",\"status\":\"created\",\"pid\":4711,"
                + "\"annotations\":{\"" + Sidecar.ANNOTATION + "\":\"" + sidecar + "\"}}";
        return new NftHook().execute(arguments, new ByteArrayInputStream(state.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "prestart", "createContainer"})
    void aStageItDoesNotLoadTheRulesetAtRefusesTheContainer(final String stage, @TempDir final Path dir)
            throws Exception {

        // Every stage but createRuntime was a successful nothing - also none at all - so a descriptor without the
        // stage, or with another, started the container with no ruleset.
        assertThat(stage.isEmpty() ? execute(dir) : execute(dir, stage)).isEqualTo(1);
    }

    @org.junit.jupiter.api.Test
    void poststopHasNothingToUnload(@TempDir final Path dir) throws Exception {

        assertThat(execute(dir, "poststop")).isZero();
    }
}
