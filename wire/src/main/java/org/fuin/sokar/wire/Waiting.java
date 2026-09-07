package org.fuin.sokar.wire;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Whether a task is waiting for a person to answer something.
 * <p>
 * <strong>A signal, not a guess.</strong> A task blocked on a question nobody noticed and a task
 * grinding through a build both look identical from outside - both are a running container - and
 * that difference is the one that costs an afternoon. So the thing that asks the question says it
 * is asking, and stops saying it when the answer arrives. Nothing here is inferred from how long
 * something has been quiet: a quiet task is not a waiting one.
 * <p>
 * A file rather than a socket, because whoever reads it is not the process that wrote it and may
 * arrive long afterwards. The file is small and its presence is the whole of the answer; the
 * destination inside it is what to show.
 */
public final class Waiting {

    /** Name of the file in a task's state directory. */
    public static final String FILE = "waiting";

    private final Path stateDirectory;

    /**
     * Constructor.
     *
     * @param stateDirectory Where the task keeps its files.
     */
    public Waiting(Path stateDirectory) {
        this.stateDirectory = stateDirectory;
    }

    /**
     * Records that a question has been put and nothing has answered it.
     *
     * @param about What is being asked about, as it is shown to the operator.
     */
    public void asking(String about) {
        try {
            Files.writeString(stateDirectory.resolve(FILE), about, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // The question is still on screen; only the ability to say so is lost.
            return;
        }
    }

    /**
     * Records that nothing is waiting any more.
     * <p>
     * Called however the question ended - answered, refused or run out - because a marker left
     * behind would make a task that is working look like one that is stuck, which is the same
     * mistake in the other direction.
     */
    public void answered() {
        try {
            Files.deleteIfExists(stateDirectory.resolve(FILE));
        } catch (IOException ex) {
            return;
        }
    }

    /**
     * Returns what a task is waiting to be told, if it is waiting.
     *
     * @param stateDirectory Where the task keeps its files.
     * @return What is being asked about, or {@code null} when nothing is.
     */
    @Nullable
    public static String about(Path stateDirectory) {
        try {
            final Path file = stateDirectory.resolve(FILE);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            final String about = Files.readString(file, StandardCharsets.UTF_8).strip();
            return about.isEmpty() ? null : about;
        } catch (IOException ex) {
            return null;
        }
    }
}
