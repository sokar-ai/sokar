package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PackageSteps}.
 */
class PackageStepsTest {

    private static final String BILL = """
            {"bomFormat": "CycloneDX", "specVersion": "1.6",
             "components": [
               {"name": "sokar-agent-claude", "components": [{"name": "claude-code"}]},
               {"name": "jspecify"}
             ]}""";

    @Test
    void readsEveryComponentNestedOnesIncluded() {
        // The fetched CLI sits inside the agent's own component, not beside it.
        assertThat(PackageSteps.components(BILL)).containsExactly("claude-code", "jspecify", "sokar-agent-claude");
    }

    @Test
    void refusesJsonThatIsNotACycloneDxBill() {
        assertThatThrownBy(() -> PackageSteps.components("{\"bomFormat\": \"SPDX\", \"components\": [{\"name\": \"x\"}]}"))
                .isInstanceOf(AssertionError.class).hasMessage("not a CycloneDX bill");
        assertThatThrownBy(() -> PackageSteps.components("[]"))
                .isInstanceOf(AssertionError.class).hasMessage("not a CycloneDX bill");
    }

    @Test
    void refusesWhatIsNotJson() {
        assertThatThrownBy(() -> PackageSteps.components("cat: /usr/share/doc/x: No such file"))
                .isInstanceOf(AssertionError.class).hasMessageStartingWith("not JSON: ");
    }

}
