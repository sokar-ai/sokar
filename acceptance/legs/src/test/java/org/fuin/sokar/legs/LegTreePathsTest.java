package org.fuin.sokar.legs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegTreePathsTest {

    /** This tree's root, two levels up from the module. */
    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();

    /** A binary a build leaves: {@code <module>/target/<image name>}. */
    private static final Pattern BUILT = Pattern.compile("([a-z][a-z0-9/-]*)/target/([a-z][a-z0-9-]*)");

    @TempDir
    Path dir;

    @Test
    void everyBinaryTheLegNamesIsAnImageAModuleItBuildsMakes() throws IOException {
        // The install copied app/target/sokar after the grouping moved the module to apps/app: the build passed,
        // and both legs failed one step later, on rented machines only.
        final List<String> binaries = binaries();
        assertThat(binaries).as(Leg.BINARIES).isNotEmpty().contains("apps/app/target/sokar");
        for (final String binary : binaries) {
            assertBuiltByTheLeg(binary);
        }
    }

    @Test
    void everyBinaryTheInstallScriptCopiesByNameIsAnImageAModuleItBuildsMakes() throws IOException {
        final Matcher built = BUILT.matcher(Files.readString(ROOT.resolve(Leg.INSTALL_SCRIPT)));
        final List<String> named = new ArrayList<>();
        while (built.find()) {
            named.add(built.group());
        }
        assertThat(named).as("what " + Leg.INSTALL_SCRIPT + " names").isNotEmpty();
        for (final String binary : named) {
            assertBuiltByTheLeg(binary);
        }
    }

    @Test
    void everyBinaryThePackagesAreMadeOfComesBackFromTheLeg() throws IOException {
        // Publish packages what the leg sent home: a path the packaging reads that the leg does not fetch is a
        // package built from nothing.
        final Set<String> packaged = new LinkedHashSet<>();
        try (Stream<Path> poms = Files.walk(ROOT.resolve("dist"))) {
            for (final Path pom : poms.filter(path -> path.endsWith("pom.xml")).toList()) {
                final Matcher read = Pattern.compile("\\$\\{project\\.basedir}/\\.\\./\\.\\./(" + BUILT.pattern() + ")")
                        .matcher(Files.readString(pom));
                while (read.find()) {
                    packaged.add(read.group(1));
                }
            }
        }
        assertThat(packaged).as("the binaries dist/ packages").isNotEmpty();
        assertThat(binaries()).containsAll(packaged);
    }

    @Test
    void theInstallPutsEachBinaryWhereAPackageWould() throws Exception {
        final Path tree = Files.createDirectories(dir.resolve("tree"));
        Files.createDirectories(tree.resolve("ci"));
        for (final String file : List.of(Leg.BINARIES, Leg.INSTALL_SCRIPT)) {
            Files.copy(ROOT.resolve(file), tree.resolve(file));
        }
        for (final String binary : binaries()) {
            executable(tree.resolve(binary));
        }
        executable(tree.resolve("builds/stub/target/sokar-build-stub"));
        for (final String kept : List.of("providers", "egress")) {
            Files.createDirectories(tree.resolve(kept));
            try (Stream<Path> yaml = Files.list(ROOT.resolve(kept))) {
                for (final Path file : yaml.filter(path -> path.toString().endsWith(".yaml")).toList()) {
                    Files.copy(file, tree.resolve(kept).resolve(file.getFileName()));
                }
            }
        }
        final Path home = Files.createDirectories(dir.resolve("home"));

        sh(tree, home, "sh " + Leg.INSTALL_SCRIPT);

        assertThat(home.resolve(".local/bin/sokar")).isRegularFile();
        assertThat(home.resolve(".local/bin/sokard")).isRegularFile();
        assertThat(home.resolve(".local/bin/sokar-hook-nft")).isRegularFile();
        assertThat(home.resolve(".local/share/sokar/agents/sokar-agent-stub")).isRegularFile();
        assertThat(home.resolve(".local/share/sokar/builds/stub-forge")).isExecutable();
        assertThat(home.resolve(".local/share/sokar/providers")).isNotEmptyDirectory();
        assertThat(home.resolve(".local/share/sokar/egress")).isNotEmptyDirectory();
    }

    @Test
    void theFilesTheMachineImagesBuildTakesFromTheTreeAreThere() {
        // sokar-buildtools' snapshot runs these from a checkout of this tree; a released tool cannot follow a move.
        assertThat(ROOT.resolve(Leg.BUILD_SCRIPT)).isRegularFile();
        assertThat(ROOT.resolve("selinux/install-selinux-policy.sh")).isRegularFile();
        assertThat(ROOT.resolve("systemd/sokard.service")).isRegularFile();
    }

    private static void assertBuiltByTheLeg(final String binary) throws IOException {
        final Matcher built = BUILT.matcher(binary);
        assertThat(built.matches()).as(binary + " is <module>/target/<image name>").isTrue();
        final String module = built.group(1);
        assertThat(legModules()).as("the modules " + Leg.BUILD_SCRIPT + " builds, for " + binary).contains(module);
        assertThat(Files.readString(ROOT.resolve(module).resolve("pom.xml")))
                .as("the image names " + module + "/pom.xml gives, for " + binary)
                .contains("<imageName>" + built.group(2) + "</imageName>");
    }

    private static List<String> binaries() throws IOException {
        return Files.readAllLines(ROOT.resolve(Leg.BINARIES)).stream().map(String::strip)
                .filter(line -> !line.isEmpty()).toList();
    }

    private static List<String> legModules() throws IOException {
        final Matcher list = Pattern.compile("(?m)^MODULES=(\\S+)$").matcher(Files.readString(ROOT.resolve(Leg.BUILD_SCRIPT)));
        assertThat(list.find()).as("a MODULES= line in " + Leg.BUILD_SCRIPT).isTrue();
        return List.of(list.group(1).split(","));
    }

    private static void executable(final Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "#!/bin/sh\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    private static void sh(final Path tree, final Path home, final String command)
            throws IOException, InterruptedException {
        final ProcessBuilder builder = new ProcessBuilder("sh", "-c", command).directory(tree.toFile())
                .redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        final Process process = builder.start();
        final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).as(out).isZero();
    }
}
