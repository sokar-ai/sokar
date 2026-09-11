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
        @org.jspecify.annotations.Nullable String upstream, Limits limits, Egress egress,
        java.util.List<String> packageSources) {

    /**
     * Where apt fetches from, when a project names nothing.
     * <p>
     * The CDN first, deliberately. Measured from a machine on an ordinary network on 2026-09-11,
     * while Ubuntu's archive was disrupted:
     *
     * <pre>
     * azure.archive.ubuntu.com   http    HTTP 200    0.18s
     * archive.ubuntu.com         http    HTTP 200   18.86s
     * </pre>
     *
     * Every image build was hanging until Sokar's own ten-minute cap, on two rented machines per
     * run, reporting nothing until it expired. A default that answers is worth more than one that
     * is canonical.
     *
     * <strong>http, not https, and that is not an oversight.</strong> This mirror serves no TLS -
     * https against it times out rather than refusing, so a default written with the wrong scheme
     * would put a source into every image that never answers. apt takes its integrity from the
     * signed Release file rather than from the transport, which is why every Ubuntu mirror is
     * plain http. Measured: https to the same host, HTTP 000 after 25s.
     */
    public static final java.util.List<String> DEFAULT_PACKAGE_SOURCES = java.util.List.of(
            "http://azure.archive.ubuntu.com/ubuntu/", "http://archive.ubuntu.com/ubuntu/");

    /**
     * Constructor without package sources, which take their default.
     *
     * @param name Short name.
     * @param description Human-readable description.
     * @param securityClass How much the agent is trusted.
     * @param baseImage Image the task image is built from.
     * @param imageSnippet Extra container-build lines, or {@code null}.
     * @param upstream Repository the work belongs to, or {@code null}.
     * @param limits What a task may consume.
     * @param egress What it may reach.
     */
    public Project(String name, String description, SecurityClass securityClass, String baseImage,
            @org.jspecify.annotations.Nullable String imageSnippet,
            @org.jspecify.annotations.Nullable String upstream, Limits limits, Egress egress) {
        this(name, description, securityClass, baseImage, imageSnippet, upstream, limits, egress,
                null);
    }

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
    /**
     * Returns where apt should fetch from, declared or default.
     *
     * @return Never empty.
     */
    public java.util.List<String> effectivePackageSources() {
        return packageSources == null ? DEFAULT_PACKAGE_SOURCES : packageSources;
    }

    /**
     * Tells whether the project named its own sources.
     * <p>
     * Apart from the default so that a base image with no apt can be refused for a project that
     * asked for something, and left alone for one that asked for nothing.
     *
     * @return {@code true} when 'image.package_sources' was written.
     */
    public boolean declaresPackageSources() {
        return packageSources != null;
    }

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
        if (packageSources != null && packageSources.isEmpty()) {
            // An empty list is not "use the default" - it is a project that named sources and
            // named none, which would silently leave the base image's own and read as configured.
            throw new ProjectException("Project '" + name + "' declares 'image.package_sources'"
                    + " with nothing in it. Remove the key to use the default.");
        }
        for (final String source : packageSources == null ? java.util.List.<String>of() : packageSources) {
            if (!source.startsWith("http://") && !source.startsWith("https://")) {
                // Refused here rather than inside a container, where it is an apt parse error in
                // a file nobody wrote by hand.
                throw new ProjectException("Project '" + name + "': package source '" + source
                        + "' is not an http or https URL");
            }
        }
        packageSources = packageSources == null ? null : java.util.List.copyOf(packageSources);
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
