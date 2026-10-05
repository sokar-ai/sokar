package org.fuin.sokar.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.sokar.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Hook}, using stand-ins for the three real hooks.
 * <p>
 * The exit code is the entire contract with the OCI runtime, so that is what is asserted.
 */
class HookTest {

    private final ByteArrayOutputStream messages = new ByteArrayOutputStream();

    private static final class Failing extends Hook {
        Failing(boolean failClosed) {
            super("test", failClosed);
        }

        @Override
        protected void run(String stage, OciState state, Sidecar sidecar) {
            throw new IllegalStateException("deliberate");
        }
    }

    private static final class Succeeding extends Hook {
        private OciState seen;

        Succeeding() {
            super("test", true);
        }

        @Override
        protected void run(String stage, OciState state, Sidecar sidecar) {
            seen = state;
        }
    }

    private String state(String sidecarPath) {
        final String annotations = sidecarPath == null ? "{}"
                : "{\"" + Sidecar.ANNOTATION + "\":\"" + sidecarPath + "\"}";
        return "{\"ociVersion\":\"1.0.2\",\"id\":\"abc123abc123\",\"status\":\"created\","
                + "\"pid\":4711,\"annotations\":" + annotations + "}";
    }

    private Path sidecarFile(Path dir) throws IOException {
        final Path file = dir.resolve("sidecar.json");
        new Sidecar(Sidecar.VERSION, "uc", "guarded",
                dir.resolve("ruleset.nft").toString(), dir.resolve("dns.conf").toString(),
                "/usr/bin/sokar", dir.toString()).writeTo(file);
        // What Sokar records right after it created the task, before its hooks run.
        Sidecar.recordId(dir, "abc123abc123");
        return file;
    }

    private int execute(Hook hook, String stateJson) {
        return hook.execute(new String[] { "createRuntime" },
                new ByteArrayInputStream(stateJson.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(messages, true, StandardCharsets.UTF_8));
    }

    @Test
    void ignoresAContainerThatIsNotSokars(@TempDir Path dir) {

        // The hook directory is global to the user, so every unrelated container on the machine
        // reaches this code. Touching them would be a bug with a very wide blast radius.
        assertThat(execute(new Failing(true), state(null))).isZero();
        assertThat(messages.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void passesTheRuntimeStateThrough(@TempDir Path dir) throws IOException {

        final Succeeding hook = new Succeeding();

        assertThat(execute(hook, state(sidecarFile(dir).toString()))).isZero();
        assertThat(hook.seen.id()).isEqualTo("abc123abc123");
        assertThat(hook.seen.pid()).isEqualTo(4711L);
    }

    @Test
    void aFailClosedHookStopsTheContainer(@TempDir Path dir) throws IOException {

        assertThat(execute(new Failing(true), state(sidecarFile(dir).toString()))).isEqualTo(1);
        assertThat(messages.toString(StandardCharsets.UTF_8)).contains("deliberate");
    }

    @Test
    void aSoftFailHookLetsTheContainerStart(@TempDir Path dir) throws IOException {

        assertThat(execute(new Failing(false), state(sidecarFile(dir).toString()))).isZero();
        assertThat(messages.toString(StandardCharsets.UTF_8)).contains("deliberate");
    }

    @Test
    void anUnreadableSidecarStopsAFailClosedHook(@TempDir Path dir) {

        assertThat(execute(new Succeeding(), state(dir.resolve("missing.json").toString())))
                .isEqualTo(1);
    }

    @Test
    void writesWhatItDidToTheStateDirectory(@TempDir Path dir) throws IOException {

        execute(new Succeeding(), state(sidecarFile(dir).toString()));

        assertThat(Files.readString(dir.resolve("hooks.log"))).contains("test createRuntime ok");
    }

    @Test
    void writesWhyItRefusedTheContainer(@TempDir Path dir) throws IOException {

        // The runtime reports only an exit code, so a container refused by a hook would otherwise
        // leave no readable record of the reason.
        execute(new Failing(true), state(sidecarFile(dir).toString()));

        assertThat(Files.readString(dir.resolve("hooks.log")))
                .contains("test createRuntime failed: deliberate");
    }

    @Test
    void anUnwritableLogDoesNotStopTheHook(@TempDir Path dir) throws IOException {

        final Path file = dir.resolve("sidecar.json");
        new Sidecar(Sidecar.VERSION, "uc", "guarded", dir.resolve("r.nft").toString(),
                dir.resolve("dns.conf").toString(), "/usr/bin/sokar",
                "/proc/nowhere-writable").writeTo(file);
        Sidecar.recordId(dir, "abc123abc123");

        // A hook that cannot write its log has still done its job. On the fail-closed path,
        // throwing here would stop a container for the wrong reason.
        assertThat(execute(new Succeeding(), state(file.toString()))).isZero();
    }

    private static final class Erring extends Hook {
        Erring(boolean failClosed) {
            super("test", failClosed);
        }

        @Override
        protected void run(String stage, OciState state, Sidecar sidecar) {
            throw new ExceptionInInitializerError("deliberate");
        }
    }

    @Test
    void anErrorIsWrittenDownAndAnsweredLikeAnyFailure(@TempDir Path dir) throws IOException {

        // Only an Exception was caught: an Error - a class that did not initialise, a stack that overflowed - left
        // nothing in the log, and refused the container even for a hook that only reports.
        assertThat(execute(new Erring(false), state(sidecarFile(dir).toString()))).isZero();
        assertThat(execute(new Erring(true), state(sidecarFile(dir).toString()))).isEqualTo(1);
        assertThat(Files.readString(dir.resolve("hooks.log"))).contains("failed");
    }

    @org.junit.jupiter.api.Test
    void aContainerThatIsNotTheTaskItsSidecarBelongsToGetsNothingAndStopsNothing(
            @org.junit.jupiter.api.io.TempDir Path dir) throws IOException {

        // Any container can carry the annotation and name a real task's sidecar (the operator, 2026-10-04).
        final Path sidecar = sidecarFile(dir);
        Sidecar.recordId(dir, "0f0f0f0f0f0f");
        final Succeeding hook = new Succeeding();

        assertThat(execute(hook, state(sidecar.toString()))).as("refused before it runs").isEqualTo(1);
        assertThat(hook.seen).as("given nothing").isNull();
        assertThat(hook.execute(new String[] { "poststop" }, new ByteArrayInputStream(state(sidecar.toString())
                .getBytes(StandardCharsets.UTF_8)), new java.io.PrintStream(new java.io.ByteArrayOutputStream())))
                .as("and after it stopped, nothing of the task's is touched").isZero();
    }
}
