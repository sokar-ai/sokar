package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Holds the packages to the podman the setup script demands.
 * <p>
 * <strong>The script refuses podman 4; a package that depends on plain {@code podman} does not.</strong> So a
 * machine that installs the package without the script - a person's own {@code apt install} - got a Sokar that
 * {@code sokar doctor} then failed, on a release the script would have stopped before installing anything.
 */
class PodmanFloorTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().getParent().getParent();

    @Test
    void theSetupScriptRefusesEverythingBelowPodmanFive() throws IOException {
        final Matcher floor = Pattern.compile("\"\\$OFFERED_MAJOR\" -lt (\\d+)").matcher(read("dist/dist-setup/sokar-setup.sh"));

        assertThat(floor.find()).as("the script's floor, where it compares the major it was offered").isTrue();
        assertThat(floor.group(1)).as("the floor both packages below are held to").isEqualTo("5");
    }

    @Test
    void theDebianPackageDependsOnPodmanFiveOrNewer() throws IOException {
        // Debian and Ubuntu carry podman without an epoch, so the version alone is what apt compares.
        assertThat(dependsLine(read("dist/dist-deb/src/deb/control/control"))).contains("podman (>= 5)");
    }

    @Test
    void theRpmRequiresPodmanFiveOrNewerWithFedorasEpoch() throws IOException {
        // Fedora's podman carries epoch 5: rpm reads a requirement without one as epoch 0, so plain '>= 5' let
        // 5:4.9.3 through - measured with rpm 6.0.1 against a package of that version.
        assertThat(read("dist/dist-rpm/pom.xml")).contains("<require>podman &gt;= 5:5.0</require>")
                .doesNotContain("<require>podman</require>");
    }

    private static String dependsLine(String control) {
        return control.lines().filter(line -> line.startsWith("Depends:")).findFirst().orElse("");
    }

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }
}
