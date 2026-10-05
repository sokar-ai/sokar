package org.fuin.sokar.gate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Orders what a push changes by what reading it is worth, before a person reads any of it.
 * <p>
 * <strong>Ranking by consequence, not by suspicion.</strong> Some changes are dangerous by kind,
 * whoever made them and why: a CI definition, a build script, a dependency manifest or lockfile,
 * something that runs when the repository is checked out or opened, a file made executable, a
 * symbolic link. The list is fixed and shipped with Sokar. A list inside the repository could be
 * edited by the very change it is meant to catch, and a list per project outside it is one more
 * thing nobody maintains. A dangerous change is named first however small it is: a one-line change
 * to a workflow is the finding, and the 4,000-line reformat beside it is not.
 * <p>
 * <strong>And what is almost never the finding goes last:</strong> a file whose change is
 * whitespace alone, and a file that says it was generated. Neither is hidden. Both are only moved
 * behind what a person should read first, and a dangerous file is never either of them.
 * <p>
 * Nothing here detects an injection, and nothing here claims to. It changes what a person reads first
 * and how much they must read at all.
 */
public final class ReviewRanking {

    /** How much a file's change is worth reading, in the order a person should meet them. */
    public enum Rank {
        /** Dangerous by kind: read first, however small. */
        DANGEROUS,
        /** Everything that is neither dangerous by kind nor almost never the finding. */
        ORDINARY,
        /** A file that says it was generated. */
        GENERATED,
        /** A change of whitespace alone. */
        REFORMATTING
    }

    /**
     * One changed file, as the review shows it.
     *
     * @param path Its path in the repository, after the change.
     * @param status {@code A}, {@code M}, {@code D} or {@code T}, as git reports it.
     * @param added Lines added, or -1 for a binary file.
     * @param removed Lines removed, or -1 for a binary file.
     * @param rank What reading it is worth.
     * @param reason Why it has that rank, in words; empty for an ordinary file.
     */
    public record File(String path, String status, int added, int removed, Rank rank, String reason) {
    }

    /**
     * A push as a person should review it.
     *
     * @param files What it changes, the files to read first at the top.
     * @param patch The patch, in the same order.
     */
    public record Review(List<File> files, String patch) {
    }

    /**
     * What git said about one file, before it is ranked.
     *
     * @param path Its path.
     * @param status Its status letter.
     * @param oldMode Its mode before, {@code 000000} when it is new.
     * @param newMode Its mode after, {@code 000000} when it is gone.
     * @param added Lines added, or -1 for a binary file.
     * @param removed Lines removed, or -1 for a binary file.
     * @param whitespaceOnly Whether its change is whitespace alone.
     * @param head The start of its new content, or {@code null} when it is gone or was not read.
     */
    record Change(String path, String status, String oldMode, String newMode, int added, int removed,
            boolean whitespaceOnly, @Nullable String head) {
    }

    private static final String CI = "a CI definition: it runs with the CI's credentials";

    private static final String BUILD = "a build script: it runs on every build";

    private static final String DEPENDENCIES = "a dependency manifest or lockfile: it decides what code is fetched and run";

    private static final String ON_CHECKOUT = "it runs, or is read by a tool, when the repository is checked out or opened";

    private static final String SIGNERS = "a signer list: it decides whose messages and which machines are believed";

    /** Directories whose every file is dangerous by kind, with the reason. */
    private static final Map<String, String> DIRECTORIES = Map.ofEntries(
            Map.entry(".github/workflows/", CI), Map.entry(".github/actions/", CI), Map.entry(".circleci/", CI),
            Map.entry(".buildkite/", CI), Map.entry(".woodpecker/", CI), Map.entry(".gitlab/", CI),
            Map.entry(".mvn/", BUILD), Map.entry("gradle/wrapper/", BUILD),
            Map.entry(".githooks/", ON_CHECKOUT), Map.entry(".husky/", ON_CHECKOUT),
            Map.entry(".vscode/", ON_CHECKOUT), Map.entry(".devcontainer/", ON_CHECKOUT),
            Map.entry(".idea/", ON_CHECKOUT));

    /** File names that are dangerous by kind wherever they are, with the reason. */
    private static final Map<String, String> NAMES = names();

    /** File names by pattern, for the families a fixed name cannot cover. */
    private static final Map<Pattern, String> PATTERNS = Map.of(
            Pattern.compile("requirements[\\w.-]*\\.(txt|in)"), DEPENDENCIES,
            Pattern.compile("[^/]+\\.gemspec"), DEPENDENCIES,
            Pattern.compile("[^/]+\\.(cs|fs|vb)proj"), DEPENDENCIES,
            Pattern.compile("(docker-)?compose(\\.[\\w-]+)?\\.ya?ml"), BUILD);

    /** What a file that was generated says about itself near its top. */
    private static final List<String> GENERATED_MARKS = List.of("@generated", "DO NOT EDIT", "DO NOT MODIFY",
            "Code generated by", "GENERATED CODE");

    private ReviewRanking() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static Map<String, String> names() {
        final Map<String, String> names = new java.util.HashMap<>();
        for (final String name : List.of(".gitlab-ci.yml", "Jenkinsfile", ".travis.yml", "azure-pipelines.yml",
                "bitbucket-pipelines.yml", ".drone.yml", ".woodpecker.yml", "appveyor.yml", ".cirrus.yml")) {
            names.put(name, CI);
        }
        for (final String name : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
                "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd", "Makefile",
                "GNUmakefile", "makefile", "CMakeLists.txt", "build.xml", "setup.py", "setup.cfg", "build.rs",
                "Rakefile", "Dockerfile", "Containerfile", "justfile", "Justfile", "Taskfile.yml", "meson.build",
                "BUILD", "BUILD.bazel", "WORKSPACE", "WORKSPACE.bazel", "MODULE.bazel", ".bazelrc", "build.sbt",
                "Directory.Build.props", "Directory.Build.targets")) {
            names.put(name, BUILD);
        }
        for (final String name : List.of("package.json", "package-lock.json", "npm-shrinkwrap.json", "yarn.lock",
                "pnpm-lock.yaml", "pnpm-workspace.yaml", ".npmrc", ".yarnrc", ".yarnrc.yml", "bun.lockb", "bun.lock",
                "Cargo.toml", "Cargo.lock", "go.mod", "go.sum", "go.work", "pyproject.toml", "poetry.lock", "Pipfile",
                "Pipfile.lock", "uv.lock", "Gemfile", "Gemfile.lock", "composer.json", "composer.lock", "mix.exs",
                "mix.lock", "pubspec.yaml", "pubspec.lock", "Package.swift", "Package.resolved",
                "packages.lock.json", "Directory.Packages.props", "nuget.config", "NuGet.Config",
                "libs.versions.toml", "deno.json", "deno.lock")) {
            names.put(name, DEPENDENCIES);
        }
        for (final String name : List.of(".gitattributes", ".gitmodules", ".pre-commit-config.yaml",
                "lefthook.yml", ".lefthook.yml", ".envrc", ".tool-versions", ".mise.toml", "mise.toml")) {
            names.put(name, ON_CHECKOUT);
        }
        // A machine's enrolment adds its key here; a new or removed signer is the finding, however small.
        names.put("allowed_signers", SIGNERS);
        names.put("machine-signers", SIGNERS);
        return Map.copyOf(names);
    }

    /**
     * Ranks what git reported, the files a person should read first at the top.
     *
     * @param changes What git said, one entry per file.
     * @return The files, in the order a person should meet them.
     */
    static List<File> rank(List<Change> changes) {
        final List<File> files = new ArrayList<>();
        for (final Change change : changes) {
            final String danger = danger(change);
            final Rank rank;
            final String reason;
            if (danger != null) {
                rank = Rank.DANGEROUS;
                reason = danger;
            } else if (change.whitespaceOnly()) {
                rank = Rank.REFORMATTING;
                reason = "whitespace only";
            } else if (generated(change)) {
                rank = Rank.GENERATED;
                reason = "says it was generated";
            } else {
                rank = Rank.ORDINARY;
                reason = "";
            }
            files.add(new File(change.path(), change.status(), change.added(), change.removed(), rank, reason));
        }
        files.sort(Comparator.comparing(File::rank).thenComparing(File::path));
        return List.copyOf(files);
    }

    /**
     * Says why a change is dangerous by kind, or nothing.
     *
     * @param change What git said about it.
     * @return The reason, or {@code null}.
     */
    static @Nullable String danger(Change change) {
        if ("120000".equals(change.newMode()) && !"120000".equals(change.oldMode())) {
            return "a symbolic link: it can point anywhere, outside the repository too";
        }
        final String byPath = dangerByPath(change.path());
        if (byPath != null) {
            return byPath;
        }
        if (change.newMode().endsWith("755") && !change.oldMode().endsWith("755")) {
            return "made executable";
        }
        return null;
    }

    /**
     * Says why a path is dangerous by kind, or nothing.
     *
     * @param path The path in the repository.
     * @return The reason, or {@code null}.
     */
    public static @Nullable String dangerByPath(String path) {
        for (final Map.Entry<String, String> directory : DIRECTORIES.entrySet()) {
            if (path.startsWith(directory.getKey()) || path.contains("/" + directory.getKey())) {
                return directory.getValue();
            }
        }
        final String name = path.substring(path.lastIndexOf('/') + 1);
        final String byName = NAMES.get(name);
        if (byName != null) {
            return byName;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        for (final Map.Entry<Pattern, String> pattern : PATTERNS.entrySet()) {
            if (pattern.getKey().matcher(lower).matches()) {
                return pattern.getValue();
            }
        }
        return null;
    }

    private static boolean generated(Change change) {
        final String name = change.path().substring(change.path().lastIndexOf('/') + 1);
        if (name.endsWith(".min.js") || name.endsWith(".min.css")) {
            return true;
        }
        final String head = change.head();
        return head != null && GENERATED_MARKS.stream().anyMatch(head::contains);
    }

    /**
     * Writes the order git should print the patch in: one path per line, escaped for its patterns.
     *
     * @param files The ranked files.
     * @return The order file's content.
     */
    static String orderFile(List<File> files) {
        final StringBuilder order = new StringBuilder();
        for (final File file : files) {
            order.append(file.path().replaceAll("([\\\\*?\\[])", "\\\\$1")).append('\n');
        }
        return order.toString();
    }
}
