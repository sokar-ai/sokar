package org.fuin.sokar.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Reads the packages this build wrote, and holds each to the version its packaging must give it.
 * <p>
 * A test of the pom saw nothing when a plugin wrote its own version into a package: the version an installer compares
 * is the one in the built file, so that is what is read.
 */
class PackageVersionTest {

    private static String property(final String name) {
        final String value = System.getProperty(name);
        assertThat(value).as(name + " is set by -Pdist").isNotNull();
        return value;
    }

    /** The project's version as the packaging maps it: -SNAPSHOT as ~snapshot.<run><suffix>, a release unchanged. */
    private static String expected() {
        final String version = property("sokar.check.projectVersion");
        return version.endsWith("-SNAPSHOT")
                ? version.substring(0, version.length() - "-SNAPSHOT".length()) + "~snapshot."
                        + property("sokar.check.run") + System.getProperty("sokar.check.suffix", "")
                : version;
    }

    /** The newest file of a kind in a build's target: the one this build wrote, where earlier ones lie beside it. */
    private static Path newest(final String directory, final String glob) throws IOException {
        try (Stream<Path> files = Files.list(Path.of(directory))) {
            return files.filter(file -> file.getFileSystem().getPathMatcher("glob:" + glob)
                    .matches(file.getFileName())).max(Comparator.comparing(file -> {
                        try {
                            return Files.getLastModifiedTime(file);
                        } catch (IOException ex) {
                            throw new java.io.UncheckedIOException(ex);
                        }
                    })).orElseThrow(() -> new AssertionError("no " + glob + " in " + directory));
        }
    }

    /** What a command printed; its complaints apart, since rpm warns of a database a Debian host does not have. */
    private static String run(final String... command) throws Exception {
        final Path complaints = Files.createTempFile("sokar-check", ".err");
        try {
            final Process process = new ProcessBuilder(command).redirectError(complaints.toFile()).start();
            final String said = new String(process.getInputStream().readAllBytes()).strip();
            assertThat(process.waitFor()).as(String.join(" ", command) + ": " + said + Files.readString(complaints))
                    .isZero();
            return said;
        } finally {
            Files.deleteIfExists(complaints);
        }
    }

    private static void debCarries(final Path deb, final String name) throws Exception {
        assertThat(run("dpkg-deb", "-f", deb.toString(), "Package", "Version").lines().toList())
                .as(deb.toString()).containsExactly("Package: " + name, "Version: " + expected());
    }

    private static void rpmCarries(final Path rpm, final String name) throws Exception {
        assertThat(run("rpm", "-qp", "--qf", "%{NAME} %{VERSION} %{RELEASE}", rpm.toString()))
                .as(rpm.toString()).isEqualTo(name + " " + expected() + " 1");
    }

    @Test
    void sokarsPackagesCarryTheProjectsVersion() throws Exception {
        debCarries(newest(property("sokar.check.deb"), "sokar_*.deb"), "sokar");
        rpmCarries(newest(property("sokar.check.rpm"), "sokar-*.rpm"), "sokar");
    }

    @Test
    void theStubAgentsPackagesCarryTheProjectsVersion() throws Exception {
        debCarries(newest(property("sokar.check.stub"), "sokar-agent-stub_*.deb"), "sokar-agent-stub");
        rpmCarries(newest(property("sokar.check.stub"), "sokar-agent-stub-*.rpm"), "sokar-agent-stub");
    }

    @Test
    void theMappingIsTheOneInstallersOrder() {
        // Below the release, as '~' sorts in dpkg and rpm: a snapshot left as -SNAPSHOT outranks its release.
        assertThat(expected()).doesNotContain("SNAPSHOT");
        assertThat(List.of(expected())).allMatch(version -> !version.endsWith("-"));
    }
}
