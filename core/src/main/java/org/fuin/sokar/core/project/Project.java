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
 * @param repositories The work repositories it names, in the order the file names them. Empty for
 *        a project that has only its own, which is what a project still being planned looks like.
 *        Its own repository is not in here - see {@link #ownRepository()}.
 * @param credentials What every task of the project holds beyond its agent's own credential: a vault
 *        entry's name to the destination it is for, in the order the file names them. A run may add to
 *        these and never withdraw one. Anyone who can start a task in the project can use them.
 */
public record Project(String name, String description, SecurityClass securityClass, String baseImage,
        @org.jspecify.annotations.Nullable String imageSnippet,
        @org.jspecify.annotations.Nullable String upstream, Limits limits, Egress egress,
        java.util.@org.jspecify.annotations.Nullable List<String> packageSources, Mail mail,
        java.util.List<Repository> repositories, java.util.Map<String, String> credentials,
        @org.jspecify.annotations.Nullable Builds builds) {

    /**
     * Constructor for a project whose tasks learn nothing of a build elsewhere - every project before they could.
     *
     * @param name Project name.
     * @param description What it is for.
     * @param securityClass How contained its tasks are.
     * @param baseImage Image its task image is built from.
     * @param imageSnippet Extra build fragment, or {@code null}.
     * @param upstream Where approved work goes, or {@code null}.
     * @param limits What a task may consume.
     * @param egress What a task may reach.
     * @param packageSources Where apt fetches from, or {@code null}.
     * @param mail The peers its tasks may address.
     * @param repositories The work repositories it names.
     * @param credentials Vault entries every task holds, by the destination each is for.
     */
    public Project(String name, String description, SecurityClass securityClass, String baseImage,
            @org.jspecify.annotations.Nullable String imageSnippet, @org.jspecify.annotations.Nullable String upstream,
            Limits limits, Egress egress, java.util.@org.jspecify.annotations.Nullable List<String> packageSources,
            Mail mail, java.util.List<Repository> repositories, java.util.Map<String, String> credentials) {
        this(name, description, securityClass, baseImage, imageSnippet, upstream, limits, egress, packageSources,
                mail, repositories, credentials, null);
    }

    /**
     * Constructor for a project that declares no credentials beyond its agent's own - every project before
     * a task could hold more than one.
     *
     * @param name Project name.
     * @param description What it is for.
     * @param securityClass How contained its tasks are.
     * @param baseImage Image a task's image is built from.
     * @param imageSnippet Extra build lines, or {@code null}.
     * @param upstream The repository the work belongs to, or {@code null}.
     * @param limits What a task may consume.
     * @param egress What a task may reach.
     * @param packageSources Where apt fetches from, or {@code null}.
     * @param mail The peers its tasks may address.
     * @param repositories The work repositories it names.
     */
    public Project(String name, String description, SecurityClass securityClass, String baseImage,
            @org.jspecify.annotations.Nullable String imageSnippet, @org.jspecify.annotations.Nullable String upstream,
            Limits limits, Egress egress, java.util.@org.jspecify.annotations.Nullable List<String> packageSources,
            Mail mail, java.util.List<Repository> repositories) {
        this(name, description, securityClass, baseImage, imageSnippet, upstream, limits, egress, packageSources,
                mail, repositories, java.util.Map.of());
    }

    /**
     * Constructor for a project whose only repository is its own.
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
            java.util.@org.jspecify.annotations.Nullable List<String> packageSources, Mail mail) {
        this(name, description, securityClass, baseImage, imageSnippet, upstream, limits, egress,
                packageSources, mail, java.util.List.of());
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
            java.util.@org.jspecify.annotations.Nullable List<String> packageSources) {
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
        credentials = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(credentials));
        for (final java.util.Map.Entry<String, String> credential : credentials.entrySet()) {
            if (!credential.getKey().matches("[a-z0-9][a-z0-9_-]{0,30}")
                    || !credential.getValue().matches("[a-z0-9][a-z0-9-]{0,30}")) {
                throw new ProjectException("Project '" + name + "': credential '" + credential.getKey() + "' for '"
                        + credential.getValue() + "' - both are names: lower-case letters, digits and hyphens");
            }
        }
        if (securityClass == SecurityClass.OFFLINE && !credentials.isEmpty()) {
            // The broker would reach the destination for the task, and an offline project reaches nothing.
            throw new ProjectException("Project '" + name + "' is offline, so it can declare no"
                    + " credentials. Remove the 'credentials' section or raise the security class.");
        }
        if (securityClass == SecurityClass.OFFLINE && !egress.isEmpty()) {
            // Said rather than ignored. An offline project whose declaration were quietly dropped
            // would look configured and behave as though it were not, which is the failure this
            // whole area exists to avoid.
            throw new ProjectException("Project '" + name + "' is offline, so it can declare no"
                    + " egress. Remove the 'egress' section or raise the security class.");
        }
        if (securityClass == SecurityClass.OFFLINE && upstream != null && !upstream.isBlank()) {
            // The host cloned the mirror from it on the first start, so offline meant "offline after the first start".
            // An offline project never connects: its repository comes in as a file.
            throw new ProjectException("Project '" + name + "' is offline, so it names no upstream: nothing connects"
                    + " out for it, not even the host. Remove 'upstream' and bring the repository in as a file with"
                    + " 'sokar gate restore', or start from a checkout of it on this machine.");
        }
        if (securityClass == SecurityClass.OFFLINE && repositories != null) {
            for (final Repository repository : repositories) {
                if (repository.upstream() != null && !repository.upstream().isBlank()) {
                    throw new ProjectException("Project '" + name + "' is offline, so its repository '"
                            + repository.name() + "' names no upstream either. Remove its 'upstream' and bring it"
                            + " in as a file with 'sokar gate restore'.");
                }
            }
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
        repositories = repositories == null ? java.util.List.of()
                : java.util.List.copyOf(repositories);
        final java.util.Set<String> named = new java.util.LinkedHashSet<>();
        for (final Repository repository : repositories) {
            if (repository.name().equals(name)) {
                // That name is already taken by the project's own repository, and a project where
                // one name meant two repositories would hand a task the wrong mirror without ever
                // saying so.
                throw new ProjectException("Project '" + name + "' names a repository '"
                        + repository.name() + "', which is the name of the project's own"
                        + " repository. Call it something else.");
            }
            if (!named.add(repository.name())) {
                throw new ProjectException("Project '" + name + "' names the repository '"
                        + repository.name() + "' twice");
            }
            if (securityClass == SecurityClass.ONLINE && !repository.hasUpstream()) {
                // Online means the agent's own remote is the upstream, so a repository without one
                // is a repository nothing can be cloned from. Refused here rather than inside a
                // container, where it is a git error against an empty address.
                throw new ProjectException("Project '" + name + "' is online, so its repository '"
                        + repository.name() + "' needs an upstream");
            }
        }
    }

    /**
     * Returns the project's own repository - where {@code project.yml}, the planning and the
     * issues live.
     * <p>
     * It is named after the project, which is why a declared repository may not take that name. It is worked in only
     * when the file names no other repository; see {@link #workRepositories()}.
     *
     * @return Never {@code null}. Every project has one.
     */
    public Repository ownRepository() {
        return new Repository(name, upstream, description);
    }

    /**
     * Returns every repository a task may be started for, the project's own first.
     *
     * @return At least one entry.
     */
    public java.util.List<Repository> allRepositories() {
        final java.util.List<Repository> all = new java.util.ArrayList<>();
        all.add(ownRepository());
        all.addAll(repositories);
        return java.util.List.copyOf(all);
    }

    /**
     * Returns the repositories work happens in: the ones the file names, or the project's own when it names none.
     * <p>
     * A project that names repositories keeps its own for its file, its planning and its issues, and no task is
     * started there - that repository's content decides what every task may reach.
     *
     * @return At least one entry.
     */
    public java.util.List<Repository> workRepositories() {
        return repositories.isEmpty() ? java.util.List.of(ownRepository()) : repositories;
    }

    /**
     * Returns the names of the repositories work happens in, in the order they are offered.
     *
     * @return At least one name.
     */
    public java.util.List<String> workRepositoryNames() {
        return workRepositories().stream().map(Repository::name).toList();
    }

    /**
     * Returns the repository of one name.
     *
     * @param repository As a person named it.
     * @return The repository, or {@code null} when this project has none of that name.
     */
    public @org.jspecify.annotations.Nullable Repository repository(final String repository) {
        return allRepositories().stream().filter(each -> each.name().equals(repository))
                .findFirst().orElse(null);
    }

    /**
     * Returns what a task on one repository may reach: the project's grants plus that
     * repository's.
     * <p>
     * <strong>Added, never replaced.</strong> Egress is a list of grants, and a repository's
     * declaration must not take away what the project gave - not least because allowing a blocked
     * connection writes itself back into the repository's block, and a grant that removes a grant
     * is the wrong direction for the most dangerous key in this file.
     * <p>
     * The project's entries come first and duplicates are dropped, so a set named in both places
     * is named once and reads as the project's.
     *
     * @param repository One of this project's repositories.
     * @return What a task of that repository may reach.
     */
    public Egress egressFor(final Repository repository) {
        if (repository.egress().isEmpty()) {
            return egress;
        }
        // A refusal of either place holds for the task: nothing a repository adds takes one back.
        return new Egress(merged(egress.sets(), repository.egress().sets()),
                merged(egress.domains(), repository.egress().domains()),
                merged(egress.refused(), repository.egress().refused()));
    }

    private static java.util.List<String> merged(java.util.List<String> first,
            java.util.List<String> second) {
        final java.util.Set<String> all = new java.util.LinkedHashSet<>(first);
        all.addAll(second);
        return java.util.List.copyOf(all);
    }

    /**
     * Returns what a task on one repository may consume: the project's limits with that
     * repository's over them, key by key.
     * <p>
     * <strong>Absent means the project's, never the default.</strong> A repository that names only
     * {@code pids} keeps the project's memory - including a memory the project deliberately
     * raised. Falling back to {@link Limits#defaults()} per key would quietly undo that for one
     * repository, and nothing on screen would mention it.
     *
     * @param repository One of this project's repositories.
     * @return The limits a task of that repository runs under.
     */
    public Limits limitsFor(final Repository repository) {
        return repository.limits().over(limits);
    }

    /**
     * Returns the names a task may be started for, in the order they are offered.
     * <p>
     * What a refusal prints when nobody said which repository the work is for.
     *
     * @return At least one name.
     */
    public java.util.List<String> repositoryNames() {
        return allRepositories().stream().map(Repository::name).toList();
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
