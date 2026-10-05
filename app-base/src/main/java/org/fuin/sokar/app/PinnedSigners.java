package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The keys this machine pinned for each project it follows, by fingerprint.
 * <p>
 * Read from the pinned signers file, whose lines are labelled with the project a key was named for. Only the
 * fingerprint is said: it is what a person compares with the key they meant to pin, and nothing else about a key is
 * needed to tell keys apart.
 */
public final class PinnedSigners {

    private PinnedSigners() {
    }

    /**
     * Returns the fingerprints pinned for each project.
     *
     * @param file The pinned signers file, which need not exist.
     * @return Project name to its fingerprints, in the order they were pinned. Empty when nothing is pinned.
     */
    public static Map<String, List<String>> of(final Path file) {
        final Map<String, List<String>> found = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return found;
        }
        final List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            // Said as nothing pinned rather than failing a listing: the follow itself reads the same file and says why.
            return found;
        }
        for (final String line : lines) {
            final String[] fields = line.strip().split("\\s+");
            if (fields.length < 3 || fields[0].startsWith("#")) {
                continue;
            }
            final String fingerprint = SignedBy.fingerprintOf(fields[1] + " " + fields[2]);
            if (fingerprint == null) {
                continue;
            }
            final List<String> mine = found.computeIfAbsent(fields[0], ignored -> new ArrayList<>());
            if (!mine.contains(fingerprint)) {
                mine.add(fingerprint);
            }
        }
        return found;
    }
}
