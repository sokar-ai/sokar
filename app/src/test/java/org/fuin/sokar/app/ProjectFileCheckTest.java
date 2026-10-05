package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProjectFileCheck}: a draft checked against this machine, each finding said as what it blocks.
 */
class ProjectFileCheckTest {

    @TempDir
    Path dir;

    private SokarContext context() {
        return context(new FakeCommandRunner());
    }

    private SokarContext context(FakeCommandRunner runner) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            default -> null;
        }, dir);
        return new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private static final String HEAD = """
            project:
              name: "p"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    @Test
    void whatSokarRefusesAnywhereBlocksTheCommit() {
        assertThat(ProjectFileCheck.check(context(), "project: [").refused()).singleElement().asString()
                .contains("Cannot parse");
        assertThat(ProjectFileCheck.check(context(), HEAD + "mail:\n  upstream: \"git@example.org:p.git\"\n").refused())
                .singleElement().asString().contains("belongs under 'project:'");
    }

    @Test
    void whatOnlyThisMachineLacksWarnsAndAKeyALaterSokarKnowsToo() {
        final ProjectFileCheck.Result result = ProjectFileCheck.check(context(), HEAD + """
                gate:
                  on: true
                credentials:
                  search: nowhere
                mail:
                  peers:
                    person: { address: "room:", trust: external }
                """);

        assertThat(result.refused()).isEmpty();
        assertThat(result.warnings()).anyMatch(each -> each.contains("'gate' is not a setting this machine's Sokar"))
                .anyMatch(each -> each.contains("credential 'search' is for 'nowhere'"))
                .anyMatch(each -> each.contains("no 'room' transport is installed"));
    }

    @Test
    void anEgressSetThisMachineLacksIsWhatItsFollowWouldRefuse() {
        final ProjectFileCheck.Result result = ProjectFileCheck.check(context(),
                HEAD + "egress:\n  sets: [no-such-set]\n");

        assertThat(result.refused()).isEmpty();
        assertThat(result.refusedHere()).singleElement().asString().contains("no-such-set");
    }

    @Test
    void aTransportJudgesItsOwnSectionWhenItOffersTo() throws java.io.IOException {
        final FakeCommandRunner runner = new FakeCommandRunner();
        adapter(context(runner), "room");
        runner
                .answering("room describe", "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"settings\"]}")
                .answering("room settings", "{\"refused\":[\"'tls_verify' is on or off, not 'maybe'\"],"
                        + "\"warnings\":[\"tls_verify off is for development only\"]}");

        final ProjectFileCheck.Result result = ProjectFileCheck.check(context(runner), HEAD + """
                mail:
                  transports:
                    room:
                      tls_verify: maybe
                """);

        assertThat(result.refused()).containsExactly("mail.transports.room: 'tls_verify' is on or off, not 'maybe'");
        assertThat(result.warnings()).contains("mail.transports.room: tls_verify off is for development only");
        assertThat(runner.only("room settings").input()).as("the section, as the transport's setup gets it")
                .contains("\"tls_verify\":\"maybe\"");
    }

    @Test
    void aTransportThatDoesNotOfferTheCheckLeavesItsSectionOpen() throws java.io.IOException {
        final FakeCommandRunner runner = new FakeCommandRunner();
        adapter(context(runner), "room");
        runner
                .answering("room describe", "{\"scheme\":\"room\",\"lifecycle\":[\"setup\"]}");

        final ProjectFileCheck.Result result = ProjectFileCheck.check(context(runner),
                HEAD + "mail:\n  transports:\n    room:\n      anything: 1\n");

        assertThat(result.refused()).isEmpty();
        assertThat(runner.lines()).noneMatch(each -> each.contains("room settings"));
    }

    private static void adapter(SokarContext context, String scheme) throws java.io.IOException {
        final Path adapter = java.nio.file.Files.createDirectories(
                context.paths().messaging().transportDirectory().locations().get(0)).resolve(TransportDirectory.PREFIX + scheme);
        java.nio.file.Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
    }
}
