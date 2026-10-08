package org.fuin.sokar.build.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledBuildReaderTest {

    private static final Target TARGET = new Target("git@forge.example:owner/name.git", "", "secret");

    @TempDir
    Path directory;

    @Test
    void asksAReaderOverItsSocketAndHearsEveryAnswerAsItWasGiven() throws IOException {
        try (InstalledBuildReader reader = new InstalledBuildReader(java(FakeReader.class), "fake", socket())) {

            assertThat(reader.forge()).isEqualTo("fake");
            assertThat(reader.head(TARGET, "main")).isEqualTo("a".repeat(40));
            assertThat(reader.head(TARGET, "gone")).as("a branch the forge does not have").isEmpty();
            assertThat(reader.look(TARGET, "c".repeat(40), Build.Logs.FAILURE)).isEqualTo(new Build(Build.SUCCESS, ""));
            assertThat(reader.look(TARGET, "c".repeat(40), Build.Logs.ALL).jobs())
                    .as("which logs are wanted crosses the socket").singleElement()
                    .satisfies(job -> assertThat(job.logText()).isEqualTo("all green\n"));
            final Build failed = reader.look(TARGET, "f".repeat(40), Build.Logs.FAILURE);
            assertThat(failed.verdict()).isEqualTo(Build.FAILURE);
            assertThat(failed.jobs()).singleElement().satisfies(job -> {
                assertThat(job.name()).isEqualTo("Build / unit tests");
                assertThat(job.result()).isEqualTo("failure");
                assertThat(job.log()).as("the log as bytes, a NUL among them").isEqualTo(FakeReader.LOG);
            });
        }
    }

    @Test
    void hearsARefusalAsTheRefusalItIsWithWhenToAskAgain() throws IOException {
        try (InstalledBuildReader reader = new InstalledBuildReader(java(FakeReader.class), "fake", socket())) {

            assertThatThrownBy(() -> reader.look(TARGET, "r".repeat(40), Build.Logs.FAILURE))
                    .isInstanceOfSatisfying(BuildRefused.class,
                    refused -> {
                        assertThat(refused.reason()).isEqualTo(BuildRefused.Reason.RATE_LIMITED);
                        assertThat(refused.retryAfterSeconds()).isEqualTo(30);
                        assertThat(refused.detail()).contains("rate limit");
                    });
            assertThatThrownBy(() -> reader.head(new Target(TARGET.upstream(), "", "wrong"), "main"))
                    .isInstanceOfSatisfying(BuildRefused.class, refused -> assertThat(refused.reason())
                            .isEqualTo(BuildRefused.Reason.CREDENTIAL_REFUSED));
        }
    }

    @Test
    void refusesAReaderThatSpeaksAnotherVersionOfTheProtocol() throws IOException {
        assertThatThrownBy(() -> new InstalledBuildReader(java(OtherVersion.class), "fake", socket()))
                .isInstanceOf(BuildReaderException.class)
                .hasMessageContaining("version 99").hasMessageContaining("this Sokar speaks version " + BuildProtocol.VERSION);
    }

    @Test
    void refusesAReaderOfAnotherForge() throws IOException {
        assertThatThrownBy(() -> new InstalledBuildReader(java(FakeReader.class), "github", socket()))
                .isInstanceOf(BuildReaderException.class).hasMessageContaining("reads 'fake', not 'github'");
    }

    @Test
    void saysWhatAReaderSaidThatEndedWithoutBecomingReady() throws IOException {
        assertThatThrownBy(() -> new InstalledBuildReader(script("echo 'no config here'; exit 3"), "fake", socket()))
                .isInstanceOf(BuildReaderException.class)
                .hasMessageContaining("exited without becoming ready").hasMessageContaining("no config here");
    }

    @Test
    void findsAReaderInTheFirstDirectoryThatHoldsItAndNamesWhereItLookedWhenNoneDoes() throws IOException {
        final Path own = Files.createDirectories(directory.resolve("own"));
        final Path packaged = Files.createDirectories(directory.resolve("packaged"));
        executable(packaged.resolve("fake"), "exit 0");

        assertThat(InstalledBuildReader.find("fake", List.of(own, packaged))).isEqualTo(packaged.resolve("fake"));
        executable(own.resolve("fake"), "exit 0");
        assertThat(InstalledBuildReader.find("fake", List.of(own, packaged))).as("the account's own first")
                .isEqualTo(own.resolve("fake"));
        assertThatThrownBy(() -> InstalledBuildReader.find("github", List.of(own, packaged)))
                .isInstanceOf(BuildReaderException.class).hasMessageContaining("No build reader for 'github'")
                .hasMessageContaining(own.toString()).hasMessageContaining(packaged.toString());
        assertThatThrownBy(() -> InstalledBuildReader.find("../fake", List.of(own)))
                .as("a name is never a path").isInstanceOf(BuildReaderException.class);
    }

    /** A reader that answers Describe with a protocol version no Sokar speaks. */
    public static final class OtherVersion {

        /**
         * Serves.
         *
         * @param arguments {@code serve <socket>}.
         */
        public static void main(final String[] arguments) {
            try (VarlinkServer server = new VarlinkServer(Path.of(arguments[1]), BuildProtocol.INTERFACE)) {
                server.method("Describe", (parameters, replies) -> replies.last(Map.of("protocolVersion", 99,
                        "forge", "fake")));
                System.out.println("ready " + arguments[1]);
                System.out.flush();
                server.run();
            }
        }
    }

    private Path socket() {
        return directory.resolve("r.sock");
    }

    private Path java(final Class<?> main) throws IOException {
        return script("exec '" + Path.of(System.getProperty("java.home"), "bin", "java") + "' -cp '"
                + System.getProperty("java.class.path") + "' '" + main.getName() + "' \"$@\"");
    }

    private Path script(final String body) throws IOException {
        return executable(directory.resolve("reader-" + System.nanoTime()), body);
    }

    private static Path executable(final Path file, final String body) throws IOException {
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
