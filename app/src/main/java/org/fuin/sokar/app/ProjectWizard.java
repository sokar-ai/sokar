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
     * Returns the package set matching a base image.
     * <p>
     * The two families need different hosts, and which one applies is a property of the image
     * rather than of the project - so it is chosen here instead of asked.
     *
     * @param baseImage Image the task image is built from.
     * @return Set name.
     */
    private static String osPackages(String baseImage) {
        final String image = baseImage.toLowerCase(Locale.ROOT);
        return image.contains("fedora") || image.contains("rocky") || image.contains("centos")
                || image.contains("almalinux") || image.contains("rhel")
                        ? "os-packages-fedora" : "os-packages-debian";
    }

    /**
     * Asks for each field, offering a default, and writes the file.
     *
     * @param file Where to write.
     * @param in Where answers are read from.
     * @param out Where questions are written.
     * @return {@code true} if the file was written, {@code false} if the answer was no.
     */
    /**
     * Returns the sets to suggest for a base image.
     * <p>
     * A suggestion, and it stays on this side of the handover. Moving it into
     * {@link ProjectCreation} would put sets nobody asked for into every project created over the
     * contract, where the file is rendered for review before anybody agrees to it.
     *
     * @param baseImage The base image.
     * @param securityClass The class; an offline project reaches nothing.
     * @return Set names, possibly empty.
     */
    static java.util.List<String> suggestedSets(String baseImage, SecurityClass securityClass,
            java.util.Set<String> available) {
        if (securityClass == SecurityClass.OFFLINE) {
            return java.util.List.of();
        }
        // Only what this machine has. Suggesting a set by name that is not installed used to be
        // invisible - the file was written and the name failed at the first task start. Now the
        // same suggestion would refuse to create the project at all, so an absent set has to drop
        // out of the offer rather than block it.
        return java.util.stream.Stream.of(osPackages(baseImage), "git-hosting")
                .filter(available::contains).toList();
    }

    static boolean create(SokarContext context, Path file, BufferedReader in,
            PrintWriter out) {
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

        // Handed over rather than written here. One renderer and one set of checks, so a file the
        // wizard writes and one the contract writes cannot come to differ - and the wizard gains
        // the name rule and the egress-set check it never had.
        final ProjectCreation.Result result = ProjectCreation.create(context, file, name, 
                securityClass, baseImage, null, suggestedSets(baseImage, parsed,
                        context.paths().egressSets().all().keySet()), false);
        if (result.outcome() != ProjectCreation.Outcome.CREATED) {
            out.println();
            result.problems().stream().filter(ProjectCreation.Problem::fatal).forEach(problem ->
                    out.println("sokar: " + problem.field() + ": " + problem.detail()));
            if (!result.detail().isEmpty()) {
                out.println("sokar: " + result.detail());
            }
            out.println("Nothing written.");
            return false;
        }
        out.println("Written " + file + ".");
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
