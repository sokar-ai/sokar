package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for which accounts {@link Accounts} reads from the run's properties.
 */
class AccountsTest {

    // The run's own, which a leg sets for the kit's tests too: set aside for each test, and put back.
    private static final List<String> PROPERTIES = List.of(Accounts.USERS, "sokar.acceptance.user",
            "sokar.acceptance.as");

    private final Map<String, String> before = new HashMap<>();

    @BeforeEach
    void setAside() {
        for (final String property : PROPERTIES) {
            final String value = System.getProperty(property);
            if (value != null) {
                before.put(property, value);
            }
            System.clearProperty(property);
        }
    }

    @AfterEach
    void putBack() {
        for (final String property : PROPERTIES) {
            System.clearProperty(property);
        }
        before.forEach(System::setProperty);
    }

    @Test
    void withoutSeveralAccountsTheOneAccountServesEveryScenarioAsAlways() {
        System.setProperty("sokar.acceptance.user", "claude");

        assertThat(Accounts.configured()).containsExactly("claude");
    }

    @Test
    void severalAccountsAreReadInOrderAndTheOneAccountIsNotAdded() {
        System.setProperty("sokar.acceptance.user", "build");
        System.setProperty(Accounts.USERS, " build, accept2 ,,accept3");

        assertThat(Accounts.configured()).containsExactly("build", "accept2", "accept3");
    }

    @Test
    void refusesSeveralAccountsThatWouldAllDriveOneOperatorsSokar() {
        System.setProperty(Accounts.USERS, "build,accept2");
        System.setProperty("sokar.acceptance.as", "michi");

        assertThatThrownBy(Accounts::configured).hasMessageContaining("cannot be combined");
    }

    @Test
    void refusesARunThatNamesNoAccountAtAll() {
        assertThatThrownBy(Accounts::configured).hasMessageContaining("sokar.acceptance.user");
    }

}
