package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.fuin.sokar.shield.NftRuleset;
import org.fuin.sokar.supervisor.VaultProxy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds {@code doc/reach.md} to the bounds in the code.
 * <p>
 * The page tells an operator how far a task can get. A bound that widens in the code while the page still
 * states the old one is the worst shape this can take: an operator holding a belief the system does not
 * support. So each number the page states is read off the code here, and a change to either fails the
 * build until both say the same.
 */
@Tag("documents")
class ReachDocumentTest {

    private static String page() throws IOException {
        return Files.readString(Path.of("../doc/reach.md"), StandardCharsets.UTF_8);
    }

    @Test
    void statesHowMuchOneRequestToTheProviderCanCarry() throws IOException {
        assertThat(page()).contains("up to **" + VaultProxy.BODY_LIMIT / (1024 * 1024) + " MB**");
    }

    @Test
    void statesThePortsADeclaredHostIsOpenedOn() throws IOException {
        assertThat(page()).contains("on ports " + NftRuleset.DEFAULT_PORTS.stream().map(String::valueOf)
                .collect(Collectors.joining(" and ")) + " only");
    }

    @Test
    void namesEveryCredentialHeaderThatIsDropped() throws IOException {
        final String page = page();
        for (final String header : VaultProxy.CREDENTIAL_HEADERS) {
            assertThat(page).as(header).contains("`" + header + "`");
        }
    }

    @Test
    void statesHowMuchOfAnAnswerIsExaminedFirst() throws IOException {
        assertThat(page()).contains("The first " + VaultProxy.PEEK / 1024 + " KB of every answer");
    }

    @Test
    void namesEveryGrantThatIsRefused() throws IOException {
        final String page = page();
        for (final String grant : VaultProxy.REFUSED_GRANTS) {
            assertThat(page).as(grant).contains("`" + grant + "`");
        }
    }

    @Test
    void saysOfEveryGuardWhetherItIsAControlOrAnAccidentCatcher() throws IOException {
        // A summary of this that counted the accident-catchers as containment would be wrong.
        final String page = page();
        for (final String line : page.lines().filter(each -> each.startsWith("| ") && !each.startsWith("| |")
                && !each.startsWith("|---")).toList()) {
            assertThat(line).as(line).containsAnyOf("| control |", "| **accident-catcher** |");
        }
        assertThat(page).contains("Sokar does not detect that.");
    }
}
