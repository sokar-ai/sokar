package org.fuin.sokar.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.fuin.sokar.core.project.SecurityClass;

/**
 * Writes a {@code project.yml} for someone who does not have one yet.
 * <p>
 * Every field has a defensible default and the last one can be derived from the directory, so
 * asking someone to write the file by hand before their first task is asking them to retype
 * what we already know. It offers the defaults and takes Enter for each.
 * <p>
 * Only three fields are asked about. Limits have working defaults and an upstream is required
 * only by the {@code online} class, so a wizard that asked about them would be padding.
 */
final class ProjectWizard {

    /** What a project is called when the directory name yields nothing usable. */
    static final String FALLBACK_NAME = "project";

    /** Same base as the acceptance suites use, and the one most projects start from. */
    static final String DEFAULT_BASE_IMAGE = "ubuntu:24.04";

    private ProjectWizard() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Turns a directory name into something the project name rules accept.
     * <p>
     * A name ends up in image tags, container names and nftables set names, which are stricter
     * than a directory: lower case, digits and hyphens, starting with a letter or digit, at most
     * 63 characters. A directory called "My Project (v2)" is a perfectly ordinary thing to be
     * sitting in, and refusing it would send someone away to think of a name.
     *
     * @param directory Where the task would run.
     * @return A usable project name, never blank.
     */
    static String nameFrom(Path directory) {
        final Path absolute = directory.toAbsolutePath().normalize();
        final Path leaf = absolute.getFileName();
        final String source = leaf == null ? "" : leaf.toString();

        final String cleaned = source.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (cleaned.isEmpty()) {
            return FALLBACK_NAME;
        }
        final String trimmed = cleaned.length() > 63 ? cleaned.substring(0, 63) : cleaned;
        // Truncating can leave a trailing hyphen, which the rules reject.
        final String tidy = trimmed.replaceAll("-+$", "");
        return tidy.isEmpty() ? FALLBACK_NAME : tidy;
    }

    /**
     * Renders a project file.
     *
     * @param name Project name.
     * @param securityClass How much the agent is trusted.
     * @param baseImage Image the task image is built from.
     * @return The file's content.
     */
    static String render(String name, SecurityClass securityClass, String baseImage) {
        return """
                # Written by 'sokar task run'. Everything here can be changed; see
                # https://github.com/fuinorg/sokar#readme for what each field does.
                project:
                  name: "%s"
                  security_class: "%s"
                image:
                  base_image: "%s"
                """.formatted(name, securityClass.name().toLowerCase(Locale.ROOT), baseImage);
    }

    /**
     * Asks for each field, offering a default, and writes the file.
     *
     * @param file Where to write.
     * @param in Where answers are read from.
     * @param out Where questions are written.
     * @return {@code true} if the file was written, {@code false} if the answer was no.
     */
    static boolean create(Path file, BufferedReader in, PrintWriter out) {
        out.println("No project definition here yet. One line each, Enter takes the default.");
        out.println();

        final Path directory = file.toAbsolutePath().getParent();
        final String name = ask(in, out, "  project name",
                nameFrom(directory == null ? Path.of(".") : directory));
        final String securityClass = ask(in, out, "  security class (offline/guarded/online)",
                SecurityClass.GUARDED.name().toLowerCase(Locale.ROOT));
        final String baseImage = ask(in, out, "  base image", DEFAULT_BASE_IMAGE);

        final SecurityClass parsed;
        try {
            parsed = SecurityClass.parse(securityClass);
        } catch (RuntimeException ex) {
            out.println();
            out.println("sokar: '" + securityClass + "' is not a security class. Nothing written.");
            return false;
        }

        out.println();
        out.print("Write " + file + "? [Y/n] ");
        out.flush();
        final String answer = read(in);
        if (!(answer.isEmpty() || answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes"))) {
            out.println("Nothing written.");
            return false;
        }

        try {
            Files.writeString(file, render(name, parsed, baseImage), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        out.println("written " + file);
        return true;
    }

    private static String ask(BufferedReader in, PrintWriter out, String question,
            String fallback) {
        out.print(question + " [" + fallback + "]: ");
        out.flush();
        final String answer = read(in);
        return answer.isEmpty() ? fallback : answer;
    }

    private static String read(BufferedReader in) {
        try {
            final String line = in.readLine();
            return line == null ? "" : line.strip();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
