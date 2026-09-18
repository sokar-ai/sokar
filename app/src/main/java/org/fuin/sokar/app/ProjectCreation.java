package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.fuin.sokar.core.project.SecurityClass;
import org.jspecify.annotations.Nullable;

/**
 * Creates a project file, having checked the answers against this machine first.
 * <p>
 * <strong>The checking is the feature, not the form.</strong> An interface can already ask the
 * questions and show the result; what it cannot do is know whether an answer is acceptable -
 * whether a security class is spelled right, whether an egress set exists here, whether the name
 * will survive being turned into an image tag and an nftables set name. Guessing on the client's
 * side puts a project on disk that fails at its first task, far from where the wrong answer was
 * given.
 * <p>
 * <strong>Nothing half-created.</strong> The file is written to a temporary name and moved into
 * place, so a caller that walks away mid-write leaves either a whole project file or none.
 */
public final class ProjectCreation {

    /** What happened. */
    public enum Outcome {

        /** Written. */
        CREATED,

        /** What would be written, having written nothing. */
        PREVIEWED,

        /** A file is already there. Never overwritten: it may be somebody's whole configuration. */
        ALREADY_EXISTS,

        /** An answer this machine cannot accept. Nothing was written. */
        INVALID,

        /** The file could not be written. */
        FAILED
    }

    /**
     * Something wrong with one answer.
     *
     * @param field Which answer, as the caller named it.
     * @param detail What is wrong, in one line.
     * @param fatal Whether it stops the project being created.
     */
    public record Problem(String field, String detail, boolean fatal) { }

    /**
     * What a creation did.
     *
     * @param outcome What happened.
     * @param file Where it went, or would go.
     * @param content The file as it would be written, for review. Always filled, including on a
     *        refusal: seeing what was rejected is most of understanding why.
     * @param problems Everything wrong, fatal or not.
     * @param detail Why it failed. "" otherwise.
     */
    public record Result(Outcome outcome, String file, String content, List<Problem> problems,
            String detail) { }

    private ProjectCreation() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Checks the answers and, unless asked not to, writes the project file.
     *
     * @param context The machine to check against.
     * @param file Where to write it.
     * @param name Project name.
     * @param securityClass offline, guarded or online.
     * @param baseImage Image the task container is built from.
     * @param upstream The remote work is forwarded to, or {@code null}.
     * @param sets Egress set names.
     * @param dryRun Checks and renders, writing nothing.
     * @return What happened.
     */
    public static Result create(SokarContext context, @Nullable Path wanted, String name,
            String securityClass, String baseImage, @Nullable String upstream, List<String> sets,
            boolean dryRun) {

        final List<Problem> problems = new ArrayList<>();

        // Where it goes is Sokar's to decide when nobody said. A caller that cannot see this
        // machine's filesystem - an interface on the other end of a forwarded socket - has no way
        // to name a path, and the answer reports the one chosen so a person sees it before
        // anything is written.
        final Path file = wanted == null || wanted.toString().isBlank()
                ? context.paths().defaultProjectFile(name) : wanted;

        if (!name.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            // The same rule Project enforces. A name becomes an image tag, a container name and an
            // nftables set name, so it is stricter than a directory name - and finding that out at
            // the first task start is finding it out in the wrong place.
            problems.add(new Problem("name", "must be lower-case letters, digits and hyphens,"
                    + " starting with a letter or digit, at most 63 characters", true));
        }

        SecurityClass parsed = null;
        try {
            parsed = SecurityClass.parse(securityClass);
        } catch (RuntimeException ex) {
            problems.add(new Problem("securityClass",
                    "not a security class; expected offline, guarded or online", true));
        }

        if (baseImage.isBlank()) {
            problems.add(new Problem("baseImage", "a project needs a base image", true));
        }

        if (parsed == SecurityClass.ONLINE && (upstream == null || upstream.isBlank())) {
            problems.add(new Problem("upstream",
                    "an online project pushes to its upstream itself, so it needs one", true));
        }

        // Checked here because only this machine knows which sets it has. A set nobody declared
        // fails at task start as a name that resolves to nothing, which reads as a broken build.
        final var available = context.paths().egressSets().all().keySet();
        for (final String set : sets) {
            if (!available.contains(set)) {
                problems.add(new Problem("sets", "no egress set called '" + set + "' here;"
                        + " available: " + String.join(", ", available), true));
            }
        }

        // Not fatal, and said rather than checked: pulling an image reaches the network, takes
        // minutes and can hang, and none of that belongs in answering a question.
        if (!baseImage.isBlank() && !context.podman().hasImage(baseImage)) {
            problems.add(new Problem("baseImage", "not on this machine yet; it will be pulled the"
                    + " first time the image is built", false));
        }

        final String content = render(name, parsed == null ? SecurityClass.GUARDED : parsed,
                baseImage, upstream, sets);

        if (problems.stream().anyMatch(Problem::fatal)) {
            return new Result(Outcome.INVALID, file.toString(), content, List.copyOf(problems),
                    "");
        }
        if (Files.exists(file)) {
            // Never overwritten. It may be somebody's whole configuration, and this is the one
            // operation that would replace it without anything to restore from.
            return new Result(Outcome.ALREADY_EXISTS, file.toString(), content,
                    List.copyOf(problems), file + " already exists");
        }
        if (dryRun) {
            return new Result(Outcome.PREVIEWED, file.toString(), content, List.copyOf(problems),
                    "");
        }

        try {
            final Path directory = file.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            // Written whole or not at all: a caller that stops mid-write must not leave half a
            // project file, which the reader would refuse in a way that reads as corruption.
            //
            // No test covers this and none can: the difference between this and writing in place
            // is visible only to a process that dies between the write and the rename. Said here
            // rather than left as a line somebody deletes because nothing failed when they did.
            final Path temporary = Files.createTempFile(directory, "project", ".yml.tmp");
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ex) {
            return new Result(Outcome.FAILED, file.toString(), content, List.copyOf(problems),
                    String.valueOf(ex.getMessage()));
        }
        return new Result(Outcome.CREATED, file.toString(), content, List.copyOf(problems), "");
    }

    /**
     * Renders the file, comments and all.
     *
     * @param name Project name.
     * @param securityClass The class.
     * @param baseImage The base image.
     * @param upstream The upstream, or {@code null}.
     * @param sets Egress set names.
     * @return The file's content.
     */
    static String render(String name, SecurityClass securityClass, String baseImage,
            @Nullable String upstream, List<String> sets) {
        final StringBuilder text = new StringBuilder("""
                # Everything here can be changed. Every key this file can carry, with what it
                # is for: https://github.com/sokar-ai/sokar/blob/main/doc/project-file.md
                project:
                  name: "%s"
                  security_class: "%s"
                """.formatted(name, securityClass.name().toLowerCase(Locale.ROOT)));
        if (upstream != null && !upstream.isBlank()) {
            text.append("  upstream: \"").append(upstream).append("\"\n");
        }
        text.append("""
                image:
                  base_image: "%s"
                """.formatted(baseImage));
        if (!sets.isEmpty()) {
            text.append("""

                    # What this project's own tooling may reach. Nothing else resolves, on ports
                    # 80 and 443 only. 'sokar shield sets' lists the names.
                    egress:
                      sets: [%s]
                    """.formatted(String.join(", ", sets)));
        }
        return text.toString();
    }
}
