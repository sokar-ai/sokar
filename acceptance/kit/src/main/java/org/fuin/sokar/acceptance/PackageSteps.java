package org.fuin.sokar.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import io.cucumber.java.en.Then;
import java.io.IOException;

/**
 * What a scenario can say about the package that put Sokar, or an agent, on the machine.
 * <p>
 * These began as shell in each agent repository's {@code acceptance.sh}, three copies differing
 * by a package name. Here once, with the name coming from the scenario.
 */
public class PackageSteps {

    private final World world;

    /**
     * Constructor with the scenario's state.
     *
     * @param world What the scenario holds.
     */
    public PackageSteps(World world) {
        this.world = world;
    }

    /**
     * Asserts that the installed version is a snapshot the next build supersedes.
     * <p>
     * Asked of dpkg or rpm rather than reasoned about: a flat {@code ~SNAPSHOT} is the same version
     * every build, so {@code apt upgrade} has nothing to do and whoever installed yesterday stays
     * there. The run number is what distinguishes one from the next, and {@code 9 < 10} has to be
     * a numeric comparison or the scheme stops working at the tenth build. A machine with neither
     * tool says so rather than answering.
     *
     * @param pkg The package name.
     * @throws IOException If the machine cannot be asked.
     */
    @Then("the installed package {string} is a snapshot that the next build supersedes")
    public void installedSnapshotOrders(String pkg) throws IOException {
        final String script = String.join("\n",
                "set -u",
                "version_lt() {",
                "  if command -v dpkg >/dev/null 2>&1; then dpkg --compare-versions \"$1\" lt \"$2\";",
                "  else [ \"$(rpm --eval \"%{lua:print(rpm.vercmp('$1','$2'))}\")\" = \"-1\" ]; fi",
                "}",
                "V=\"$(dpkg-query -W -f='${Version}' " + pkg + " 2>/dev/null"
                        + " || rpm -q --qf '%{VERSION}' " + pkg + " 2>/dev/null)\"",
                "case \"$V\" in",
                "  '') echo \"NO VERSION: " + pkg + " reports none\"; exit 1;;",
                "  *-SNAPSHOT) echo \"WRONG: $V sorts ABOVE the release\"; exit 1;;",
                "  *~SNAPSHOT) echo \"WRONG: $V never supersedes the last build\"; exit 1;;",
                "  *~snapshot.*) ;;",
                "  *) echo \"RELEASE: $V\"; exit 0;;",
                "esac",
                "if ! command -v dpkg >/dev/null 2>&1 && ! command -v rpm >/dev/null 2>&1; then",
                "  echo 'CANNOT CHECK: neither dpkg nor rpm is here'; exit 1; fi",
                "R=\"${V%%~*}\"; N=\"${V##*~snapshot.}\"",
                "version_lt \"$V\" \"$R\" && version_lt \"$V\" \"$R~snapshot.$((N + 1))\""
                        + " && version_lt \"$R~snapshot.9\" \"$R~snapshot.10\""
                        + " && echo \"OK: $V\" || { echo \"WRONG: $V does not order\"; exit 1; }");
        final Machine.Output output = world.machine().run(script);
        assertThat(output.status()).as("%s", output.all().strip()).isZero();
    }

    /**
     * Asserts that the package's bill of materials is there and names a component.
     * <p>
     * Checked where it matters: on a machine that installed the package rather than in the build
     * that made it. An update gate diffs this against the published one, so a package that ships
     * none, or one describing something else, breaks that gate silently rather than loudly.
     *
     * @param path Where the package installs its bill.
     * @param component A component the bill must name.
     * @throws IOException If the machine cannot be asked.
     */
    @Then("the bill at {string} names {string}")
    public void billNames(String path, String component) throws IOException {
        final String python = String.join("\n",
                "import json, sys",
                "bom = json.load(open(sys.argv[1]))",
                "assert bom.get('bomFormat') == 'CycloneDX', 'not a CycloneDX bill'",
                "def walk(items):",
                "    for c in items or []:",
                "        yield c",
                "        yield from walk(c.get('components'))",
                "names = {c['name'] for c in walk(bom.get('components'))}",
                "assert sys.argv[2] in names, sys.argv[2] + ' is not among ' + str(sorted(names))",
                "print(len(names), 'components')");
        final Machine.Output output = world.machine().run(
                "python3 - " + Shell.quote(path) + " " + Shell.quote(component) + " <<'PY'\n"
                        + python + "\nPY");
        assertThat(output.status()).as("%s", output.all().strip()).isZero();
    }
}
