package org.fuin.sokar.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutTest {

    @Test
    void everyPlaceTheCheckReadsIsAModuleOfThisTree() {
        // Kept in a released tool, the check still looked in dist-deb/ and dist-rpm/ after the modules moved under
        // dist/, found no package and failed only on the way to publishing. In the tree, a regrouping meets this.
        final Path root = Path.of("").toAbsolutePath().getParent().getParent();
        assertThat(root.resolve("dist/package-check")).isDirectory();
        final List<String> places = new ArrayList<>(List.of(Main.DEB, Main.RPM, Main.AGENT, Main.BINARY));
        places.addAll(Main.STRAYS);
        for (final String place : places) {
            final Path module = root.resolve(place.substring(0, place.indexOf("/target")));
            assertThat(Files.isRegularFile(module.resolve("pom.xml"))).as(place + " is under a module").isTrue();
        }
        assertThat(Main.BINARY).as("the binary the app module builds").endsWith("/target/sokar");
    }
}
