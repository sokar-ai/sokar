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
 * @param mail The peers its tasks may address.
 * @param unreadWorkMayLeave Whether work nobody has read may leave this machine.
 *        <p>
 *        The one setting two features ask before anything unread goes out: a message sent without
 *        a person having seen it, and work pushed to a review branch on an upstream. It is one
 *        setting rather than two because it is one decision - a project where an agent may talk
 *        unsupervised is a project where an agent may publish unsupervised, and splitting it would
 *        let somebody answer it twice without noticing they had.
 */
public record Project(String name, String description, SecurityClass securityClass, String baseImage,
        @org.jspecify.annotations.Nullable String imageSnippet,
        @org.jspecify.annotations.Nullable String upstream, Limits limits, Egress egress,
        java.util.List<String> packageSources, Mail mail, boolean unreadWorkMayLeave) {

    /**
     * Constructor for a project that keeps unread work at home.
     *
     * @param name Project name.
     * @param description What it is for.
     * @param securityClass How contained its tasks are.
     * @param baseImage Image its task image is built from.
     * @param imageSnippet Extra build fragment, or {@code null}.
     * @param upstream Where approved work goes, or {@code null}.
     * @param limits What a task may use.
     * @param egress What a task may reach.
     * @param packageSources Where apt fetches from.
     * @param mail The peers its tasks may address.
     */
    public Project(String name, String description, SecurityClass securityClass, String baseImage,
            @org.jspecify.annotations.Nullable String imageSnippet,
            @org.jspecify.annotations.Nullable String upstream, Limits limits, Egress egress,
            java.util.List<String> packageSources, Mail mail) {
        this(name, description, securityClass, baseImage, imageSnippet, upstream, limits, egress,
                packageSources, mail, false);
    }

    /**
     * Constructor for a project that exchanges no messages.
     * <p>
     * Every caller that predates the mail section keeps working through this, and reads as what it
     * is: a project whose tasks address nobody.
     *
     * @param name Project name.
     * @param description What it is for.
     * @param securityClass How contained its tasks are.
     * @param baseImage Image its task image is built from.
     * @param imageSnippet Extra build fragment, or {@code null}.
     * @param upstream Where approved work goes, or {@code null}.
     * @param limits What a task may use.
     * @param egress What a task may reach.
     * @param packageSources Where apt fetches from.
     */
    public Project(String name, String description, SecurityClass securityClass, String baseImage,
            @org.jspecify.annotations.Nullable String imageSnippet,
            @org.jspecify.annotations.Nullable String upstream, Limits limits, Egress egress,
            java.util.List<String> packageSources) {
        this(name, description, securityClass, baseImage, imageSnippet, upstream, limits, egress,
                packageSources, Mail.none());
    }

    /**
     * Where apt fetches from, when a project names nothing.
     * <p>
     * One entry, not a list with a fallback. Measured on 2026-09-11 from a rented machine of the
     * kind the build actually runs on, pulling a 19 MB package index while Ubuntu's archive was
     * disrupted:
     *
     * <pre>
     * azure.archive.ubuntu.com   19.3 MB in  0.08s   230 MB/s
     * de.archive.ubuntu.com      19.3 MB in  0.23s    83 MB/s
     * archive.ubuntu.com          2.2 MB in 60.00s    37 kB/s   (timed out)
     * </pre>
     *
     * <strong>Why no fallback.</strong> apt spreads its requests over the URIs it is given rather
     * than holding the second in reserve, so a slow mirror beside a fast one drags the whole
     * update down to its speed. Measured on the same machine, same minute:
     *
     * <pre>
     * both mirrors   apt-get update  65.2 MB in 4m03s   whole build 4m16s
     * azure alone    apt-get update  32.6 MB in    1s   pull, update and install in 8s
     * </pre>
     *
     * The halved volume is the proof of the mechanism: with two URIs apt fetched the indices from
     * both, and the slower one set the pace. A second source is insurance only if the first
     * fails outright; against one that is merely crawling it is a tax on every build. Somebody
     * whose network prefers a different mirror says so with image.package_sources.
     *
     * <strong>Latency is the wrong measure here, and choosing on it was a mistake once already.</strong>
     * These mirrors answer a small Release file in well under a second even when they cannot
     * deliver an index at any useful rate; archive.ubuntu.com served the Release file in 8s and
     * the index at 37 kB/s. What an image build spends its time on is 65 MB of indices.
     *
     * <strong>http, not https, and that is not an oversight.</strong> This mirror serves no TLS -
     * https against it times out rather than refusing, so a default written with the wrong scheme
     * would put a source into every image that never answers. apt takes its integrity from the
     * signed Release file rather than from the transport, which is why every Ubuntu mirror is
     * plain http. Measured: https to the same host, HTTP 000 after 25s.
     */
    public static final java.util.List<String> DEFAULT_PACKAGE_SOURCES = java.util.List.of(
            "http://azure.archive.ubuntu.com/ubuntu/");

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
