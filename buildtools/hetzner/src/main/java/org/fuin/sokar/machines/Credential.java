package org.fuin.sokar.machines;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The private key that reaches a machine, however it was given.
 * <p>
 * <strong>Two sources, one cleaning.</strong> On a developer's machine the key is a file; in CI it
 * is a secret that is deliberately never written to a filesystem. Both arrive here and both are
 * normalised in the same place, because the failure this prevents is not obvious from its message:
 * a stray carriage return in a key makes it unreadable and reports {@code error in libcrypto},
 * naming neither the file nor the reason. Two implementations of that cleaning is how one of them
 * comes to lack it - which had already happened across four copies of the script this replaces.
 */
public sealed interface Credential {

    /**
     * Returns the key itself, ready to be read by an ssh client.
     *
     * @return PEM text, with a trailing newline and no carriage returns.
     * @throws IOException If a key held in a file cannot be read.
     */
    String material() throws IOException;

    /**
     * A key held in memory, as CI has it.
     *
     * @param key The key text.
     */
    record InMemory(String key) implements Credential {

        /**
         * Cleans the key and refuses one that is not there.
         *
         * @param key The key text.
         */
        public InMemory {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(
                        "No private key. There is nothing to authenticate with.");
            }
            key = key.replace("\r\n", "\n").replace("\r", "\n").strip() + "\n";
        }

        @Override
        public String material() {
            return key;
        }
    }

    /**
     * A key held in a file, as a developer has it.
     *
     * @param path Where the key is.
     */
    record InFile(Path path) implements Credential {

        @Override
        public String material() throws IOException {
            if (!Files.isRegularFile(path)) {
                throw new IOException("No private key: there is nothing at " + path);
            }
            return new InMemory(Files.readString(path, StandardCharsets.UTF_8)).material();
        }
    }

    /**
     * Takes the key from the material when there is any, and from the file otherwise.
     * <p>
     * The order is the point: CI sets the material and has no file, a developer has a file and
     * sets no material, and a machine with both is CI with a developer's checkout on it.
     *
     * @param material The key itself, or {@code null}.
     * @param path A key file, or {@code null}.
     * @return Whichever was given.
     * @throws IllegalArgumentException If neither was.
     */
    static Credential of(@Nullable String material, @Nullable Path path) {
        if (material != null && !material.isBlank()) {
            return new InMemory(material);
        }
        if (path == null) {
            throw new IllegalArgumentException(
                    "No private key. Pass the key itself or a path to one.");
        }
        return new InFile(path);
    }
}
