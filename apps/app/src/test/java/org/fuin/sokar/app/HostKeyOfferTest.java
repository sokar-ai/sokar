package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.core.config.XdgPaths;
import org.fuin.sokar.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests that a task's start at a host this machine never met shows what the host offers and asks to trust its key,
 * instead of naming {@code sokar credentials trust-host}; a trust decision is never taken by {@code --yes}.
 */
class HostKeyOfferTest {

    @TempDir
    private Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final List<String> asked = new ArrayList<>();

    private final StringWriter err = new StringWriter();

    private static final String ED25519 =
            "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl";

    private TaskLaunch launch(final Offer.Asker asker, final boolean yes) {
        final XdgPaths xdg = XdgPaths.of(name -> switch (name) {
            case "XDG_STATE_HOME" -> dir.resolve("state").toString();
            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
            case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
            default -> null;
        }, dir);
        runner.answering("ssh-keyscan", ED25519 + "\n");
        final SokarContext context = new SokarContext(runner, new SokarPaths(xdg, dir.resolve("bin")), arguments -> 0);
        return new TaskLaunch(context, new TaskLaunch.Request("t", dir.resolve("project.yml"), null, null, null, 8, null,
                false, false, "prompt", true, org.fuin.sokar.wire.TaskMode.SHELL, null, null, null, 60, null))
                .offering(new Offer(question -> {
                    asked.add(question);
                    return asker.ask(question);
                }, false, yes, null));
    }

    @Test
    void theKeyIsShownAndTrustedWhenThePersonSaysSo() {
        assertThat(launch(question -> "y", false).trustUnmet("github.com", new PrintWriter(err, true))).isTrue();
        assertThat(err.toString()).contains("ssh-ed25519").contains("SHA256:");
        assertThat(asked).singleElement().asString().contains("github.com").contains("SHA256:").endsWith("[y/N]");
    }

    @Test
    void enterAloneOrYesTrustsNothing() {
        assertThat(launch(question -> "", true).trustUnmet("github.com", new PrintWriter(err, true))).isFalse();
        assertThat(err.toString()).contains("sokar credentials trust-host github.com");
    }
}
