package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.sokar.agent.api.AgentException;
import org.fuin.sokar.agent.api.PackagedTree;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.core.project.Egress;
import org.fuin.sokar.core.project.Limits;
import org.fuin.sokar.core.project.Mail;
import org.fuin.sokar.core.project.Project;
import org.fuin.sokar.core.project.SecurityClass;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentStagingTest {

    @TempDir
    Path dir;

    private final StringWriter out = new StringWriter();

    @Test
    void readsARelativeSourceBesideTheAgentBinary() {
        // So a copy of the agent in one account's directory ships its own tree, not the machine's.
        assertThat(AgentStaging.source(Path.of("/home/core/.local/share/sokar/agents/sokar-agent-a"), "a/tree.tar.gz"))
                .isEqualTo(Path.of("/home/core/.local/share/sokar/agents/a/tree.tar.gz"));
    }

    @Test
    void takesAnAbsoluteSourceAsItIs() {
        assertThat(AgentStaging.source(Path.of("/usr/libexec/sokar/agents/sokar-agent-a"), "/usr/share/sokar/agents/a/t"))
                .isEqualTo(Path.of("/usr/share/sokar/agents/a/t"));
    }

    @Test
    void stagesATreeFoundBesideTheBinary() throws IOException {
        final Path agents = Files.createDirectories(dir.resolve("agents"));
        Files.writeString(Files.createDirectories(agents.resolve("tree")).resolve("tool"), "a tool");

        final List<String> lines = stage(agents.resolve("sokar-agent-a"), new PackagedTree("tree", "/opt/a"));

        assertThat(lines).containsExactly("COPY sokar-packaged-opt-a /opt/a");
        assertThat(paths().buildContext("p").resolve("sokar-packaged-opt-a/tool")).hasContent("a tool");
    }

    @Test
    void refusesATaskWhoseAgentShipsATreeThatIsNotThere() {
        // It built the image without it, and the task then started with no tool in it.
        assertThatThrownBy(() -> stage(dir.resolve("agents/sokar-agent-a"), new PackagedTree("gone", "/opt/a")))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("a ships /opt/a from " + dir.resolve("agents/gone"))
                .hasMessageContaining("nothing is there");
    }

    private List<String> stage(Path executable, PackagedTree tree) {
        return AgentStaging.stage("a", executable, List.of(tree), project(), paths(), new FakeCommandRunner(),
                new PrintWriter(out, true));
    }

    private SokarPaths paths() {
        return new SokarPaths(XdgPaths.of(name -> "XDG_DATA_HOME".equals(name) ? dir.resolve("data").toString() : null,
                dir), dir.resolve("bin"));
    }

    private static Project project() {
        return new Project("p", "a project", SecurityClass.GUARDED, "ubuntu:24.04", null, null, Limits.defaults(),
                Egress.none(), Project.DEFAULT_PACKAGE_SOURCES, Mail.none(), false);
    }

}
