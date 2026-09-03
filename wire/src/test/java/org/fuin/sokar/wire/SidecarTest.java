package org.fuin.sokar.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Sidecar}.
 */
class SidecarTest {

    private static final Sidecar SIDECAR = new Sidecar(Sidecar.VERSION, "uc", "guarded",
            "/run/user/1000/sokar/uc/ruleset.nft", "/run/user/1000/sokar/uc/dns.conf",
            "/run/user/1000/sokar/uc");

    @Test
    void survivesARoundTrip() {

        assertThat(Sidecar.fromJson(SIDECAR.toJson())).isEqualTo(SIDECAR);
    }

    @Test
    void survivesARoundTripThroughAFile(@TempDir Path dir) throws IOException {

        final Path file = dir.resolve("nested/sidecar.json");
        SIDECAR.writeTo(file);

        assertThat(Sidecar.readFrom(file)).isEqualTo(SIDECAR);
    }

    @Test
    void refusesAVersionItDoesNotKnow() {

        // The nft hook fails closed, so acting on a misread file is worse than refusing it.
        final String json = SIDECAR.toJson().replace("\"version\":2", "\"version\":99");

        assertThatThrownBy(() -> Sidecar.fromJson(json))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Unsupported sidecar version 99");
    }

    @Test
    void refusesAMissingField() {

        assertThatThrownBy(() -> Sidecar.fromJson("{\"version\":2,\"project\":\"uc\"}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("securityClass");
    }

    @Test
    void refusesSomethingThatIsNotAnObject() {

        assertThatThrownBy(() -> Sidecar.fromJson("[]"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("must be a JSON object");
    }

    @Test
    void namesTheAnnotationTheHooksAreGatedOn() {

        assertThat(Sidecar.ANNOTATION).isEqualTo("org.fuin.sokar.sidecar");
    }
}
