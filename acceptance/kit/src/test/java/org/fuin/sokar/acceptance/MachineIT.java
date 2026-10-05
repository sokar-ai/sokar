package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tests for {@link Machine}, run only when a machine was named.
 */
@EnabledIfSystemProperty(named = "sokar.acceptance.host", matches = ".+")
class MachineIT {

    @Test
    void probesWithTheKeyItAuthenticatedWith() throws Exception {

        // reachable() used to demand the key FILE property even when the material is in the
        // environment, which is how CI holds it. The demand threw inside the probe's own try,
        // was caught as "unreachable", and every probe then answered false - so a restart
        // scenario failed saying the machine never came back, about a machine that was fine.
        try (Machine machine = new Machine()) {
            assertThat(machine.reachable()).isTrue();
        }
    }
}
