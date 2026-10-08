package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * What {@code sokar-bom} publishes, read from the pom flatten writes for it: the reactor builds {@code bom} first, so it
 * is there when this runs.
 */
class PublishedBomTest {

    private static final Path FLATTENED = Path.of("..", "..", "bom", ".flattened-pom.xml");

    @Test
    void carriesNoFlagThatWouldPublishWhoeverTakesItAsParent() throws IOException {

        // A repository took sokar-bom as its parent, inherited both flags as false
        // and counted as published to Central, which it never was.
        final String published = Files.readString(FLATTENED);

        assertThat(published).as("a BOM, its versions there").contains("<dependencyManagement>")
                .contains("<artifactId>sokar-agent-api</artifactId>");
        assertThat(published).doesNotContain("<skipPublishing>false</skipPublishing>")
                .doesNotContain("<maven.deploy.skip>false</maven.deploy.skip>");
    }
}
