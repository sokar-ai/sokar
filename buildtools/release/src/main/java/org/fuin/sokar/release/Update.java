package org.fuin.sokar.release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.sokar.wire.Json;
import org.fuin.sokar.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * Moves a module to a new upstream version, doing exactly what a person would.
 * <p>
 * It writes the pin, the digest or the lockfile, the module's own version once it is released, and
 * one changelog line. Everything else that names the version is filtered from the pin, so it cannot
 * be left behind. The digest is never taken from a caller.
 */
public final class Update {

    /** The working tree now pins the requested version, or already did. */
    public static final int DONE = 0;

    private static final Pattern PIN = Pattern.compile("<" + Pattern.quote(Pom.PIN) + ">[^<]+</" + Pattern.quote(Pom.PIN) + ">");

    private static final Pattern DIGEST = Pattern.compile("(\\bsha256:\\s*\")[0-9a-f]{64}(\")");

    private final PrintStream out;

    private final PrintStream err;

    private final Web web;

    private final Relock relock;

    /**
     * An update that reports on two streams.
     *
     * @param out where the plan goes
     * @param err where a refusal is explained
     * @param web where upstream is read
     * @param relock how an npm tree gets its lockfile
     */
    public Update(PrintStream out, PrintStream err, Web web, Relock relock) {
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
        this.web = Objects.requireNonNull(web, "web");
        this.relock = Objects.requireNonNull(relock, "relock");
    }

    /**
     * Pins a version.
     *
     * @param pomFile the module's pom
     * @param version the release to pin
     * @param dryRun whether to say what would change and write nothing
     * @return {@link #DONE}, {@link Stop#REFUSED} or {@link Stop#UNANSWERED}
     */
    public int pin(Path pomFile, String version, boolean dryRun) {
        try {
            return move(Release.of(Pom.read(pomFile)), version, dryRun);
        } catch (Stop stop) {
            err.println(stop.getMessage());
            if (stop.code() == Stop.UNANSWERED) {
                err.println("This is not the same as 'there is no such version'.");
            }
            return stop.code();
        }
    }

    private int move(Release release, String version, boolean dryRun) throws Stop {
        if (!Versions.isVersion(version)) {
            throw Stop.refused("'" + version + "' is not a version");
        }
        final Pom pom = release.pom();
        final String was = pom.pinned();
        if (was.equals(version)) {
            out.println("already pinned to " + version + " - nothing to do");
            return DONE;
        }
        final Map<Path, String> writes = new LinkedHashMap<>();
        final Map<String, String> report = new LinkedHashMap<>();
        report.put(release.agent().toLowerCase(Locale.ROOT), was + " -> " + version);

        final Optional<String> digest = release.digests().digest(web, version);
        if (digest.isPresent()) {
            final String definition = read(release.definition());
            writes.put(release.definition(), Texts.replaceOnce(definition, DIGEST,
                    "$1" + digest.get() + "$2", "pinned sha256 in " + release.definition().getFileName()));
            report.put("sha256", digest.get() + "  (" + release.digests().describe() + ")");
        }
        final Release.NpmTree tree = release.npm();
        if (tree != null) {
            report.put("lockfile", relocked(release, tree, version, writes));
        }

        String pomText = Texts.replaceOnce(pom.text(), PIN, "<" + Pom.PIN + ">" + version + "</" + Pom.PIN + ">",
                Pom.PIN + " in pom.xml");
        final String bumped = bumped(pom.version());
        if (bumped != null) {
            pomText = Texts.replaceOnce(pomText,
                    Pattern.compile("(<artifactId>" + Pattern.quote(pom.artifactId()) + "</artifactId>\\s*<version>)[^<]+(</version>)"),
                    "$1" + Matcher.quoteReplacement(bumped) + "$2", "the module's own version");
            report.put("this module", pom.version() + " -> " + bumped);
        } else {
            report.put("this module", pom.version() + ", unchanged - the CI run number already orders snapshot packages");
        }
        writes.put(pom.file(), pomText);

        final Path changelogFile = pom.root().resolve("CHANGELOG.md");
        final String changelog = Changelog.note(read(changelogFile), release.agent(), version, was);
        writes.put(changelogFile, changelog);
        final Matcher entry = Changelog.entry(release.agent()).matcher(changelog);
        report.put("changelog", entry.find() ? entry.group() : "");

        report.forEach((label, value) -> out.println("  " + String.format("%-13s", label) + " " + value));
        if (dryRun) {
            out.println();
            out.println("--dry-run: nothing written");
            return DONE;
        }
        for (final Map.Entry<Path, String> write : writes.entrySet()) {
            try {
                Files.writeString(write.getKey(), write.getValue());
            } catch (IOException ex) {
                throw Stop.refused("cannot write " + write.getKey() + ": " + ex.getMessage(), ex);
            }
        }
        out.println();
        out.println("written. Review the diff, then: Pin " + release.agent() + " " + version);
        return DONE;
    }

    private String relocked(Release release, Release.NpmTree tree, String version, Map<Path, String> writes) throws Stop {
        if (!(release.upstream() instanceof Release.Upstream.Npm registry)) {
            throw Stop.refused("an npm tree needs " + Release.PREFIX + "upstream to be the npm registry");
        }
        if (!registry.has(web, version)) {
            throw Stop.refused(tree.packageName() + " has no version " + version);
        }
        final Path manifestFile = tree.directory().resolve("package.json");
        final Path lockFile = tree.directory().resolve("package-lock.json");
        final String manifest = Texts.replaceOnce(read(manifestFile),
                Pattern.compile("(\"" + Pattern.quote(tree.packageName()) + "\"\\s*:\\s*\")[^\"]+(\")"),
                "$1" + Matcher.quoteReplacement(version) + "$2", tree.packageName() + " in package.json");
        final String before = read(lockFile);
        final String lockfile = relock.relock(tree, manifest, before, release.pom().root().resolve("target/relock"));
        final String resolved = installed(lockfile, tree.packageName());
        if (!version.equals(resolved)) {
            throw Stop.refused("the lockfile resolved " + resolved + ", not " + version);
        }
        writes.put(manifestFile, manifest);
        writes.put(lockFile, lockfile);
        return packages(before) + " -> " + packages(lockfile) + " packages, resolved by the pinned npm";
    }

    // Only a released module moves - a snapshot is not three numbers - since its successor would be invented.
    private static @Nullable String bumped(String version) {
        if (!Versions.isVersion(version)) {
            return null;
        }
        final String[] parts = version.split("\\.");
        return parts[0] + "." + parts[1] + "." + (Integer.parseInt(parts[2]) + 1);
    }

    private static @Nullable String installed(String lockfile, String packageName) throws Stop {
        final Object entry = lockPackages(lockfile).get("node_modules/" + packageName);
        return entry instanceof Map<?, ?> found && found.get("version") instanceof String version ? version : null;
    }

    private static int packages(String lockfile) throws Stop {
        return lockPackages(lockfile).size();
    }

    private static Map<?, ?> lockPackages(String lockfile) throws Stop {
        try {
            return Json.parse(lockfile) instanceof Map<?, ?> root && root.get("packages") instanceof Map<?, ?> packages
                    ? packages : Map.of();
        } catch (JsonException ex) {
            throw Stop.refused("the lockfile is not JSON: " + ex.getMessage(), ex);
        }
    }

    private static String read(Path file) throws Stop {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw Stop.refused("cannot read " + file + ": " + ex.getMessage(), ex);
        }
    }

}
