package org.fuin.sokar.core.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Exec}.
 * <p>
 * A successful {@code execvp} does not return, so the successful case cannot be asserted in this
 * process. It is asserted in a child process instead, which is the only honest way to check that
 * the image really was replaced rather than a child having been spawned.
 */
class ExecTest {

    @Test
    void reportsAProgramThatDoesNotExist() {

        assertThatThrownBy(() -> Exec.replaceCurrentProcess(List.of("sokar-no-such-program")))
                .isInstanceOf(CommandException.class)
                // ENOENT
                .hasMessageContaining("errno=2");
    }

    @Test
    void reallyReplacesTheProcessImage() throws Exception {

        // The child prints its own pid, then execs 'sh -c' which prints the pid again. Same pid
        // means the image was replaced; a different pid would mean a child was spawned instead.
        final String java = System.getProperty("java.home") + "/bin/java";
        final Process process = new ProcessBuilder(java,
                "-cp", System.getProperty("java.class.path"),
                "--enable-native-access=ALL-UNNAMED",
                ExecChild.class.getName()).redirectErrorStream(true).start();

        final String output = new String(process.getInputStream().readAllBytes()).strip();
        process.waitFor();

        final List<String> lines = output.lines().toList();
        assertThat(lines).as("output was: %s", output).hasSize(2);
        assertThat(lines.get(1)).isEqualTo(lines.get(0));
    }

    /**
     * Prints its pid, then replaces itself with a shell that prints the pid again.
     */
    public static final class ExecChild {

        private ExecChild() {
            throw new UnsupportedOperationException("Utility class");
        }

        /**
         * Entry point.
         *
         * @param args Ignored.
         */
        public static void main(String[] args) {
            final long pid = ProcessHandle.current().pid();
            System.out.println(pid);
            System.out.flush();
            Exec.replaceCurrentProcess(List.of("sh", "-c", "echo $$"));
        }
    }
}
