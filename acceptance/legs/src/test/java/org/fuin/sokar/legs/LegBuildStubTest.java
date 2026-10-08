package org.fuin.sokar.legs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegBuildStubTest {

    @TempDir
    Path dir;

    @Test
    void buildsWithTheTreesOwnScriptAndHandsItTheRunNumber() throws Exception {
        final Path tree = Files.createDirectories(dir.resolve("tree"));
        Files.createDirectories(tree.resolve("ci"));
        Files.writeString(tree.resolve(Leg.BUILD_SCRIPT), "echo built with run \"${1:-none}\"\n");
        final String build = Leg.build("17", "271");

        assertThat(build).contains("GITHUB_RUN_ID=17 ").endsWith("sh " + Leg.BUILD_SCRIPT + " 271");
        assertThat(sh(tree, build.substring(build.indexOf("&&") + 3))).isEqualTo("built with run 271");
        assertThat(Leg.build(null, null)).endsWith("sh " + Leg.BUILD_SCRIPT);
    }

    @Test
    void installsTheStubReaderUnderTheNameTheSuitesProjectsGiveItAndNothingWhenNoneWasBuilt() throws Exception {
        final Path home = Files.createDirectories(dir.resolve("home"));
        final Path binary = dir.resolve("sokar-build-stub");

        assertThat(sh(home, Leg.installBuildStub(binary.toString()) + " && echo done")).isEqualTo("done");
        assertThat(home.resolve(".local/share/sokar/builds")).as("nothing built, nothing installed").doesNotExist();

        Files.writeString(binary, "#!/bin/sh\n");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        sh(home, Leg.installBuildStub(binary.toString()));
        assertThat(home.resolve(".local/share/sokar/builds/stub-forge")).isExecutable();
    }

    private String sh(final Path home, final String command) throws IOException, InterruptedException {
        final ProcessBuilder builder = new ProcessBuilder("sh", "-c", command).directory(home.toFile())
                .redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        final Process process = builder.start();
        final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor()).as(out).isZero();
        return out;
    }
}
