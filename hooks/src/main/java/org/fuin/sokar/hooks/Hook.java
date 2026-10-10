package org.fuin.sokar.hooks;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.fuin.sokar.wire.Sidecar;

/**
 * What the three hooks have in common: read the OCI state, find the sidecar, run, report.
 * <p>
 * <strong>Failure behavior is the whole design here.</strong> A hook that exits non-zero at
 * {@code createRuntime} stops the container from starting, which is right for the firewall and
 * wrong for everything else. Each hook says which it is, and this class makes sure the choice is
 * applied consistently rather than depending on which exception happens to escape.
 */
public abstract class Hook {

    private final String name;

    private final boolean failClosed;

    /**
     * Constructor.
     *
     * @param name Hook name, used in messages and in the log file.
     * @param failClosed Whether a failure must stop the container from starting.
     */
    protected Hook(String name, boolean failClosed) {
        this.name = name;
        this.failClosed = failClosed;
    }

    /**
     * Does the hook's work.
     *
     * @param stage Which stage fired, {@code createRuntime} or {@code poststop}. The runtime does
     *        not tell a hook this, so it arrives in the arguments and one descriptor is installed
     *        per stage.
     * @param state Container state from the runtime.
     * @param sidecar What the launcher wrote for this container.
     * @throws Exception If the work could not be done.
     */
    protected abstract void run(String stage, OciState state, Sidecar sidecar) throws Exception;

    /**
     * Runs the hook and returns the process exit code.
     *
     * @param arguments Command line arguments; the first is the stage name.
     * @param in Stream the runtime writes the state to.
     * @param err Stream for messages.
     * @return Exit code.
     */
    public int execute(String[] arguments, java.io.InputStream in, PrintStream err) {

        final String stage = arguments.length > 0 ? arguments[0] : "unknown";
        Sidecar failed = null;

        try {

            final OciState state = OciState.read(in);
            final String sidecarPath = state.annotation(Sidecar.ANNOTATION);

            if (sidecarPath == null) {
                // Not a Sokar container. The hook directory is global to the user, so this is the
                // normal case for every other container on the machine.
                return 0;
            }

            // The container the runtime runs is the one Sokar created for this sidecar, or nothing is done: any
            // container can carry the annotation and name a real task's sidecar, and a foreign one's poststop would
            // otherwise reap that task's helpers (2026-10-04).
            final Path sidecarFile = Path.of(sidecarPath);
            final Path stateDirectory = sidecarFile.getParent();
            final String recorded = stateDirectory == null ? null : Sidecar.recordedId(stateDirectory);
            if (recorded == null || !recorded.equals(state.id())) {
                err.println("sokar-hook: container " + state.id() + " is not the task " + sidecarPath
                        + " belongs to; nothing is done for it");
                // Refused before it runs; after it stopped, nothing of anybody's is touched.
                return "poststop".equals(stage) ? 0 : 1;
            }
            final Sidecar sidecar = Sidecar.readFrom(sidecarFile);
            failed = sidecar;
            log(sidecar, stage, "start");
            run(stage, state, sidecar);
            log(sidecar, stage, "ok");
            return 0;

        } catch (Throwable ex) {
            // Everything, at this process boundary: an Error - a class that did not initialise, a stack that
            // overflowed - otherwise left no line in the log and refused even a hook that only reports.
            final String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            err.println("sokar " + name + " (" + stage + "): " + message);
            // Also into the log: the runtime reports only an exit code, so without this a refused
            // container leaves no record of why anywhere the operator can read.
            if (failed != null) {
                log(failed, stage, "failed: " + message);
            }
            // A soft-fail hook reports and gets out of the way. Losing the audit reader is bad;
            // refusing to start the container because of it is worse.
            return failClosed ? 1 : 0;
        }
    }

    /**
     * Appends one line to the hook log. Never throws: a hook that cannot write its log has still
     * done its job, and on the fail-closed path an exception here would stop a container for the
     * wrong reason.
     *
     * @param sidecar Where the state directory is.
     * @param stage Stage name.
     * @param message What happened.
     */
    protected void log(Sidecar sidecar, String stage, String message) {
        try {
            final Path file = Path.of(sidecar.stateDirectory()).resolve("hooks.log");
            Files.createDirectories(file.getParent());
            Files.writeString(file, Instant.now() + " " + name + " " + stage + " " + message + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ex) {
            // Deliberately ignored, see above.
        }
    }

    /**
     * Returns the hook name.
     *
     * @return Name.
     */
    public String name() {
        return name;
    }

    /**
     * Tells whether a failure stops the container.
     *
     * @return {@code true} for fail-closed hooks.
     */
    public boolean failClosed() {
        return failClosed;
    }
}
