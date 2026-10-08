package org.fuin.sokar.legs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LegBuildScriptTest {

    /** This tree's root, two levels up from the module. */
    private static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();

    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");

    @Test
    void everyModuleTheLegBuildsIsInTheReactor() throws IOException {
        // The grouping moved app under apps/, and the list a released tool held still named app: every leg stopped
        // at "Could not find the selected project in the reactor: app", on rented machines only, after the push.
        final List<String> reactor = reactor(ROOT, "");
        final List<String> modules = modules();

        assertThat(modules).as("the modules ci/leg-build.sh builds").isNotEmpty().contains("apps/app");
        assertThat(reactor).as("the reactor's modules").containsAll(modules);
    }

    @Test
    void theScriptRunsTheModuleListItNames() throws IOException {
        assertThat(Files.readString(ROOT.resolve(Leg.BUILD_SCRIPT))).contains("-pl \"$MODULES\" -am");
    }

    private static List<String> modules() throws IOException {
        final Matcher list = Pattern.compile("(?m)^MODULES=(\\S+)$").matcher(Files.readString(ROOT.resolve(Leg.BUILD_SCRIPT)));
        assertThat(list.find()).as("a MODULES= line in " + Leg.BUILD_SCRIPT).isTrue();
        return List.of(list.group(1).split(","));
    }

    /**
     * Returns every module below a pom, as a path from the root, the way {@code -pl} names it.
     *
     * @param root The tree's root.
     * @param at Where the pom is, relative to the root; empty for the root.
     * @return The modules.
     * @throws IOException If a pom cannot be read.
     */
    private static List<String> reactor(final Path root, final String at) throws IOException {
        final List<String> found = new ArrayList<>();
        final Matcher module = MODULE.matcher(Files.readString(root.resolve(at).resolve("pom.xml")));
        while (module.find()) {
            final String path = at.isEmpty() ? module.group(1) : at + "/" + module.group(1);
            found.add(path);
            found.addAll(reactor(root, path));
        }
        return found;
    }
}
