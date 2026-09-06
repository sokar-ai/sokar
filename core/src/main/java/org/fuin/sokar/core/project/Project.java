package org.fuin.sokar.core.project;

/**
 * A project a task can run against.
 *
 * @param name Short name, used for container and image names.
 * @param description Human-readable description, may be empty.
 * @param securityClass How much the agent is trusted with the outside world.
 * @param baseImage Container image the task image is built from.
 * @param imageSnippet Extra container-build lines the operator wants in the image, or
 *        {@code null}. This is how additional tooling gets into a box.
 * @param upstream Repository the work ultimately belongs to, or {@code null}. Required by an
 *        {@link SecurityClass#ONLINE} project, where the agent pushes to it directly; for the
 *        other classes it is only where an approved push is forwarded.
 * @param limits What a task may consume.
 * @param egress What the project's own tooling may reach.
 */
public record Project(String name, String description, SecurityClass securityClass, String baseImage,
        @org.jspecify.annotations.Nullable String imageSnippet,
        @org.jspecify.annotations.Nullable String upstream, Limits limits, Egress egress) {

    /**
     * Constructor without a declared egress.
     *
     * @param name Short name.
     * @param description Human-readable description.
     * @param securityClass How much the agent is trusted.
     * @param baseImage Image the task image is built from.
     * @param imageSnippet Extra container-build lines, or {@code null}.
     * @param upstream Repository the work belongs to, or {@code null}.
     * @param limits What a task may consume.
     */
    public Project(String name, String description, SecurityClass securityClass, String baseImage,
            @org.jspecify.annotations.Nullable String imageSnippet,
            @org.jspecify.annotations.Nullable String upstream, Limits limits) {
        this(name, description, securityClass, baseImage, imageSnippet, upstream, limits,
                Egress.none());
    }

    /**
     * Constructor with the default limits.
     *
     * @param name Short name.
     * @param description Human-readable description.
     * @param securityClass How much the agent is trusted.
     * @param baseImage Image the task image is built from.
     * @param imageSnippet Extra container-build lines, or {@code null}.
     * @param upstream Repository the work belongs to, or {@code null}.
     */
    public Project(String name, String description, SecurityClass securityClass, String baseImage,
            @org.jspecify.annotations.Nullable String imageSnippet,
            @org.jspecify.annotations.Nullable String upstream) {
        this(name, description, securityClass, baseImage, imageSnippet, upstream,
                Limits.defaults());
    }

    /**
     * Constructor for a project with no upstream.
     *
     * @param name Short name.
     * @param description Human-readable description.
     * @param securityClass How much the agent is trusted.
     * @param baseImage Image the task image is built from.
     * @param imageSnippet Extra container-build lines, or {@code null}.
     */
    public Project(String name, String description, SecurityClass securityClass, String baseImage,
            @org.jspecify.annotations.Nullable String imageSnippet) {
        this(name, description, securityClass, baseImage, imageSnippet, null);
    }

    /**
     * Constructor with all data.
     *
     * @param name Short name, used for container and image names.
     * @param description Human-readable description, may be empty.
     * @param securityClass How much the agent is trusted with the outside world.
     * @param baseImage Container image the task image is built from.
     * @param imageSnippet Extra container-build lines, or {@code null}.
     */
    public Project {
        if (name.isBlank()) {
            throw new ProjectException("The project name is required");
        }
        if (!name.matches("[a-z0-9][a-z0-9-]{0,62}")) {
            // The name ends up in image tags, container names and nftables set names, all of which
            // are stricter than YAML. Rejecting here beats a confusing failure three layers down.
            throw new ProjectException("Invalid project name '" + name
                    + "', expected lower-case letters, digits and hyphens");
        }
        if (baseImage.isBlank()) {
            throw new ProjectException("The base image is required");
        }
        if (securityClass == SecurityClass.OFFLINE && !egress.isEmpty()) {
            // Said rather than ignored. An offline project whose declaration were quietly dropped
            // would look configured and behave as though it were not, which is the failure this
            // whole area exists to avoid.
            throw new ProjectException("Project '" + name + "' is offline, so it can declare no"
                    + " egress. Remove the 'egress' section or raise the security class.");
        }
        if (securityClass == SecurityClass.ONLINE && (upstream == null || upstream.isBlank())) {
            // An online project puts the agent's remote at the upstream itself, so without one
            // there is nothing for it to clone from and the class means nothing. Refused here
            // rather than at clone time, inside a container, where the failure is a git error.
            throw new ProjectException("Project '" + name + "' is online, so it needs an upstream");
        }
    }

    /**
     * Returns the operator's own build lines, split into lines.
     *
     * @return Lines, empty when the project adds nothing.
     */
    public java.util.List<String> imageSnippetLines() {
        return imageSnippet == null ? java.util.List.of()
                : imageSnippet.lines().map(String::stripTrailing).toList();
    }

    /**
     * Returns the name of the image built for this project.
     *
     * @return Image name without a tag.
     */
    public String imageName() {
        return "sokar/" + name;
    }
}
