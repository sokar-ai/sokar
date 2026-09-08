package org.fuin.sokar.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.sokar.app.SokarContext;
import org.fuin.sokar.app.SokarPaths;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.fuin.sokar.wire.varlink.VarlinkClient;
import org.fuin.sokar.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds the interface description against what the daemon actually answers.
 * <p>
 * The description exists so that an interface can be written against it without reading this
 * daemon's Java, which is only worth anything if it cannot drift. A method registered and not
 * described is one no interface author will know exists; a method described and not registered is
 * one they will call and be refused by. Both are caught here rather than by whoever is building
 * the frontend.
 * <p>
 * Deliberately not a parser: varlink's grammar is not what is at risk. What is at risk is the
 * list of names, and that is what this compares.
 */
class InterfaceDescriptionTest {

    private static SokarContext context(Path dir) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        return new SokarContext(new FakeCommandRunner(), new SokarPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    /** {@code method Name(...)} at the start of a line, which is the only place one may appear. */
    private static final Pattern METHOD = Pattern.compile("(?m)^method\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");

    /** {@code error Name(...)}, same shape. */
    private static final Pattern ERROR = Pattern.compile("(?m)^error\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");

    /** {@code type Name (} at the start of a line. */
    private static final Pattern TYPE = Pattern.compile("(?m)^type\\s+([A-Za-z][A-Za-z0-9]*)\\s*\\(");

    /** A field's type: {@code name: Thing}, {@code name: []Thing}, {@code name: ?Thing}. */
    private static final Pattern REFERENCE =
            Pattern.compile("(?m)^\\s*[a-z][A-Za-z0-9]*\\s*:\\s*\\??(?:\\[\\])?\\??([A-Za-z][A-Za-z0-9]*)");

    /** What varlink defines itself, so a reference to one is not a reference to anything here. */
    private static final Set<String> BUILT_IN =
            Set.of("string", "int", "float", "bool", "object");

    @Test
    void namesNoTypeItDoesNotDeclare() throws IOException {

        // A description is only worth anything if somebody can write a client from it alone, and
        // a field whose type is named but never declared stops exactly that - the reader has to
        // come and read this daemon's Java, which is the thing the file exists to avoid. Found
        // worth guarding when a new named type was added for an agent's commit identity: nothing
        // here would have noticed if it had been referenced and never written down.
        final String description = SokarDaemon.description();
        final Set<String> declared = new TreeSet<>();
        final Matcher types = TYPE.matcher(description);
        while (types.find()) {
            declared.add(types.group(1));
        }
        // Enums are declared the same way, so this list is every name the file introduces.
        final Set<String> referenced = new TreeSet<>();
        final Matcher fields = REFERENCE.matcher(description);
        while (fields.find()) {
            if (!BUILT_IN.contains(fields.group(1))) {
                referenced.add(fields.group(1));
            }
        }

        assertThat(referenced).isNotEmpty();
        assertThat(declared).containsAll(referenced);
    }

    @Test
    void describesEveryMethodItAnswersAndAnswersEveryMethodItDescribes(@TempDir Path dir)
            throws IOException {

        try (VarlinkServer server =
                SokarDaemon.serving(context(dir), dir.resolve("sokard.sock"))) {

            final Set<String> registered = new TreeSet<>(server.methodNames().stream()
                    .filter(name -> name.startsWith(SokarDaemon.INTERFACE + "."))
                    .map(name -> name.substring(SokarDaemon.INTERFACE.length() + 1))
                    .toList());

            assertThat(names(METHOD, SokarDaemon.description()))
                    .as("methods in " + SokarDaemon.INTERFACE + ".varlink versus those registered")
                    .isEqualTo(registered);
        }
    }

    @Test
    void describesEveryErrorItCanRaise(@TempDir Path dir) {
        // The names the daemon throws, gathered from its own source rather than from a list kept
        // beside it: a list would be the third place to forget.
        assertThat(names(ERROR, SokarDaemon.description()))
                .as("errors described versus those thrown")
                .containsAll(thrownErrorNames());
    }

    @Test
    void servesTheDescriptionToAClientThatAsks(@TempDir Path dir) throws Exception {
        // The point of the file is that a client can read the contract off a running daemon, so
        // the reading is what is tested - not that the file is on the classpath.
        try (VarlinkServer server =
                SokarDaemon.serving(context(dir), dir.resolve("sokard.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> reply = client.call(
                        "org.varlink.service.GetInterfaceDescription",
                        Map.of("interface", SokarDaemon.INTERFACE));
                assertThat(String.valueOf(reply.get("description")))
                        .isEqualTo(SokarDaemon.description());
            }
        }
    }

    @Test
    void namesItselfWithAVersionAndReportsTheBuildItIs(@TempDir Path dir) throws Exception {
        // Two different versions, deliberately. The digit on the interface name is the
        // compatibility promise a client picks by; the build version is what it shows a person.
        // Confusing them is how a frontend ends up gating a feature on a release number.
        assertThat(SokarDaemon.INTERFACE)
                .as("varlink interfaces carry their compatibility number, as Clearance1 does")
                .matches(".*[0-9]$");

        try (VarlinkServer server = SokarDaemon.serving(context(dir), dir.resolve("sokard.sock"))) {
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Map<String, Object> info =
                        client.call("org.varlink.service.GetInfo", Map.of());
                assertThat(info.get("version"))
                        .as("the build version, not a literal somebody has to remember to bump")
                        .isEqualTo(org.fuin.sokar.app.SokarVersion.version())
                        .isNotEqualTo("unknown");
                assertThat(new java.util.ArrayList<Object>((List<?>) info.get("interfaces")))
                        .contains(SokarDaemon.INTERFACE);
            }
        }
    }

    private static Set<String> names(Pattern pattern, String idl) {
        final Set<String> found = new TreeSet<>();
        final Matcher matcher = pattern.matcher(idl);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /**
     * Returns the error names {@code SokarDaemon} raises, read out of its source.
     * <p>
     * Reading the source is not elegant and is the reason this works: any other way of listing
     * them is a list that a new error can be added without touching.
     */
    private static Set<String> thrownErrorNames() {
        final Path source = Path.of("src/main/java/org/fuin/sokar/daemon/SokarDaemon.java");
        final String text;
        try {
            text = java.nio.file.Files.readString(source);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read " + source.toAbsolutePath(), ex);
        }
        final Set<String> found = new TreeSet<>();
        // Anchored on the throw, not on the concatenation: INTERFACE is also glued to the
        // resource name, and the clearance interface is concatenated the same way - both of
        // which this matched before it was narrowed.
        final Matcher matcher = Pattern.compile(
                "new VarlinkException\\(\\s*INTERFACE\\s*\\+\\s*\"\\.([A-Za-z][A-Za-z0-9]*)\"")
                .matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        // Raised by the server for anything unexpected, so it is never written in this source.
        found.add("Failed");
        assertThat(found).as("errors found in " + source).isNotEmpty();
        return found;
    }

    @Test
    void describesEveryParameterNameTheDaemonReads() throws IOException {
        // Parameter names are where an IDL quietly stops being true: they are strings on both
        // sides and nothing connects them. Every name the daemon reads out of a call must appear
        // in the description - which also refuses a name varlink cannot express, such as one
        // with a hyphen in it, because it could never have been written there.
        final String idl = SokarDaemon.description();
        final String source = java.nio.file.Files.readString(
                Path.of("src/main/java/org/fuin/sokar/daemon/SokarDaemon.java"));
        final Matcher matcher = Pattern.compile(
                "(?:text|flag|empty)\\(parameters, \"([^\"]+)\"\\)"
                + "|parameters\\.get\\(\"([^\"]+)\"\\)").matcher(source);
        final List<String> missing = new java.util.ArrayList<>();
        while (matcher.find()) {
            final String name = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            if (!Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + ":").matcher(idl).find()) {
                missing.add(name);
            }
        }
        assertThat(missing).as("parameters read by the daemon but not described").isEmpty();
    }
}
