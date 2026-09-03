package org.fuin.sokar.hooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.fuin.sokar.wire.JsonException;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link OciState}.
 */
class OciStateTest {

    @Test
    void readsWhatCrunWrites() {

        final OciState state = OciState.parse("""
                {"ociVersion":"1.0.2","id":"abc","status":"created","pid":1234,
                 "bundle":"/run/user/1000/crun/abc",
                 "annotations":{"org.fuin.sokar.sidecar":"/x.json","io.podman.annotations.init":"FALSE"}}
                """);

        assertThat(state.id()).isEqualTo("abc");
        assertThat(state.pid()).isEqualTo(1234L);
        assertThat(state.annotation("org.fuin.sokar.sidecar")).isEqualTo("/x.json");
    }

    @Test
    void toleratesAMissingPidAtPoststop() {

        // At poststop the process is already gone and the runtime omits the field.
        final OciState state = OciState.parse("{\"id\":\"abc\",\"status\":\"stopped\"}");

        assertThat(state.pid()).isZero();
        assertThat(state.annotations()).isEmpty();
    }

    @Test
    void ignoresFieldsItDoesNotKnow() {

        // The runtime is free to add fields, and a hook that broke on them would break on upgrade.
        assertThat(OciState.parse("{\"id\":\"abc\",\"somethingNew\":{\"deep\":[1,2]}}").id())
                .isEqualTo("abc");
    }

    @Test
    void refusesStateWithoutAnId() {

        assertThatThrownBy(() -> OciState.parse("{\"pid\":1}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("no 'id'");
    }
}
