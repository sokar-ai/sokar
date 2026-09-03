package org.fuin.sokar.shield;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.fuin.sokar.core.process.CommandException;
import org.fuin.sokar.core.process.FakeCommandRunner;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EgressPolicy}.
 * <p>
 * That an added element really unblocks the next attempt was verified against a running container:
 * blocked, added, open, with every other address still blocked. What is asserted here is the
 * command that does it.
 */
class EgressPolicyTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final EgressPolicy policy = new EgressPolicy(runner, 4711L);

    @Test
    void addsAnIpv4AddressToTheLiveSet() {

        policy.allow("1.1.1.1");

        // Adding to the live set rather than reloading the ruleset: a reload drops conntrack
        // state and kills every connection the agent already had open.
        assertThat(runner.only("nft").describe())
                .isEqualTo("nsenter --target 4711 --net nft add element inet sokar allowed_v4 { 1.1.1.1 }");
    }

    @Test
    void addsAnIpv6AddressToTheOtherSet() {

        policy.allow("2606:50c0::1");

        assertThat(runner.only("nft").describe()).contains("allowed_v6 { 2606:50c0::1 }");
    }

    @Test
    void entersTheContainersNetworkNamespace() {

        policy.allow("1.1.1.1");

        // Without nsenter this would edit the host's firewall instead of the container's.
        assertThat(runner.only("nft").describe()).startsWith("nsenter --target 4711 --net ");
    }

    @Test
    void reportsAFailureRatherThanClaimingTheAddressIsAllowed() {

        runner.failing("nft", 1, "Error: Could not process rule: No such file or directory");

        assertThatThrownBy(() -> policy.allow("1.1.1.1"))
                .isInstanceOf(CommandException.class)
                .hasMessageContaining("No such file or directory");
    }
}
