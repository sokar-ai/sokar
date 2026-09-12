package org.fuin.sokar.runtime;

import java.util.ArrayList;
import java.util.List;
import org.fuin.sokar.core.project.Project;

/**
 * Builds the {@code Containerfile} for a project's task image.
 * <p>
 * Three layers, in this order and for this reason:
 * <ol>
 * <li><strong>base</strong> - the distro image the project chose, plus the unprivileged user and
 *     the fetch tooling every later layer needs;</li>
 * <li><strong>agent</strong> - whatever the selected agent contributes, which is normally a pinned
 *     and digest-verified download of its CLI;</li>
 * <li><strong>project</strong> - the operator's own additions, which is how extra tooling gets
 *     into a box.</li>
 * </ol>
 * Ordering is not cosmetic: the project layer runs last so it can rely on the agent being there,
 * and both run after the user exists so nothing has to guess at ownership.
 * <p>
 * Assembled by hand rather than from a template, on purpose: the byte-exact output is pinned by
 * golden-file tests, so when this moves to a template engine the tests decide whether the result is
 * still the same image.
 */
public final class Containerfile {

    /**
     * How many lines a task's shell session remembers.
     * <p>
     * Pinned so that "what may re-entering claim" has an answer. Ten thousand lines is enough to
     * hold a build somebody walked away from and small enough that a session left open costs
     * nothing worth counting.
     */
    public static final String SCROLLBACK = "10000";

    /**
     * Where the session's configuration is written, and read from explicitly.
     * <p>
     * Shared with everything that starts or joins a session, because a second spelling of this
     * path is a second session with different rules.
     */
    public static final String TMUX_CONF = "/etc/sokar/tmux.conf";

    /**
     * Name of the one session a task has.
     * <p>
     * <strong>One, and every entry point uses it.</strong> Starting a task and attaching to it
     * used to reach the container by different routes - the start ran the agent under a plain
     * {@code exec} and only {@code attach} used a multiplexer - so attaching to a task that was
     * already running created a second, empty session beside the agent and showed a bare shell in
     * the workspace. Reported on 2026-09-12.
     */
    public static final String SESSION = "sokar";

    /**
     * Build argument marking where the agent's layers begin.
     * <p>
     * Passing a value podman has not seen invalidates its cache from this line down, so the
     * agent's tooling is rebuilt and the packages above it are not. Nothing in the image reads it:
     * it exists to be changed.
     */
    public static final String LAYER_EPOCH = "SOKAR_LAYER_EPOCH";

    private Containerfile() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Renders the Containerfile for a project with no additional layers.
     *
     * @param project The project.
     * @return File content, ending in a line separator.
     */
    /**
     * Returns a short digest of everything about a project that decides its image.
     * <p>
     * <strong>Deliberately not the whole project file.</strong> Egress, limits and the upstream
     * change what a task may do rather than what it is built from, and hashing them would report
     * an image as stale after an edit that could not have changed it - which teaches people to
     * ignore the word.
     * <p>
     * The agent is not in it either: which agent runs is chosen per task rather than per project,
     * so an image is not stale because somebody picked a different one.
     *
     * @param project The project.
     * @return Sixteen hex characters - enough to tell two recipes apart, short enough to read.
     */
    public static String fingerprint(Project project) {
        // Sokar's own half of the recipe is in here too, and it was not always: the digest used to
        // cover the project's answers alone, so a Sokar release that changed what every image
        // contains produced no drift anywhere and existing images silently kept the old recipe.
        // Found on 2026-09-12, when adding a UTF-8 locale to the image fixed nothing on a machine
        // that already had one built - which is the shape of defect this label exists to prevent.
        //
        // Rendered without the agent's layers, so the rule above still holds: which agent runs is
        // chosen per task, and an image is not stale because somebody picked a different one.
        final String recipe = project.baseImage() + "\u0000"
                + (project.imageSnippet() == null ? "" : project.imageSnippet()) + "\u0000"
                + body(project, ImageLayers.none());
        try {
            final byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(recipe.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder text = new StringBuilder();
            for (int index = 0; index < 8; index++) {
                text.append(String.format("%02x", digest[index]));
            }
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
        }
    }

    public static String render(Project project) {
        return render(project, ImageLayers.none());
    }

    /**
     * Renders the Containerfile for a project.
     *
     * @param project The project.
     * @param layers What the agent and the project contribute.
     * @return File content, ending in a line separator.
     */
    public static String render(Project project, ImageLayers layers) {

        final List<String> lines = new ArrayList<>(List.of(body(project, layers).split("\n", -1)));
        // Trailing empty element from the split; the labels follow immediately.
        lines.removeLast();

        lines.add("");
        lines.add("LABEL org.fuin.sokar.project=\"" + project.name() + "\"");
        // What this image was built from, so an image built before the project file changed can
        // be told from one that is simply absent. Without it "prepared" says only that something
        // exists, and "this will not be what you expect" is discoverable only by starting work.
        lines.add("LABEL org.fuin.sokar.recipe=\"" + fingerprint(project) + "\"");
        lines.add("LABEL org.fuin.sokar.security-class=\""
                + project.securityClass().name().toLowerCase() + "\"");

        return String.join("\n", lines) + "\n";
    }

    /**
     * Renders everything above the labels.
     * <p>
     * Separate because {@link #fingerprint(Project)} hashes it and the labels carry the
     * fingerprint: one method that did both would have to hash its own output.
     *
     * @param project The project.
     * @param layers What the agent and the project contribute.
     * @return The recipe without its labels, ending in a line separator.
     */
    private static String body(Project project, ImageLayers layers) {

        // One URIs line, space separated, which is what deb822 takes and what apt walks in order.
        final String sources = String.join(" ", project.effectivePackageSources());
        // Only a project that ASKED for sources is refused on a base with no apt. One that asked
        // for nothing gets the default and must still work on fedora and alpine, which is most of
        // them - refusing there would break every non-Debian base for a key nobody wrote.
        final String refusal = project.declaresPackageSources() ? "true" : "false";

        final List<String> lines = new ArrayList<>(List.of(
                "# Generated by Sokar. Do not edit; changes are overwritten on the next build.",
                "FROM " + project.baseImage(),
                "",
                "# curl, CA certificates, git and an ssh client: every pinned agent download needs the",
                "# first two, the workspace is cloned with the third, and an online project pushes to",
                "# a real upstream over ssh with the fourth. A base image cannot be assumed to have",
                "# any of them. Written for the three package managers Sokar's supported bases use;",
                "# a base with none of them must ship these itself, and will fail loudly here rather",
                "# than three layers further down. tmux is here so a session survives leaving it:",
                "# 'sokar task attach' runs it, and a closed window leaves the session running.",
                "USER root",
                "",
                "# Where apt fetches from, before anything fetches. Only the URIs line is replaced,",
                "# never the file rewritten: suites, components and the signing key differ between",
                "# Debian and Ubuntu and between releases, and a file written from guesses breaks an",
                "# image that worked.",
                "#",
                "# Measured 2026-09-11 on a rented machine of the kind this build runs on, pulling a",
                "# 19 MB package index while Ubuntu's archive was disrupted:",
                "#",
                "#   azure.archive.ubuntu.com   19.3 MB in  0.08s   230 MB/s",
                "#   archive.ubuntu.com          2.2 MB in 60.00s    37 kB/s  (timed out)",
                "#",
                "# One source rather than a list: apt spreads requests over the URIs it is given",
                "# instead of keeping the second in reserve, so a crawling mirror beside a fast one",
                "# costs every build. With both, apt-get update took 4m03s for 65 MB of indices and",
                "# the build hit Sokar's ten-minute cap. Somebody whose network prefers another",
                "# mirror names it with image.package_sources.",
                "#",
                "# http, not https: Ubuntu's mirrors serve no TLS, and apt takes its integrity from",
                "# the signed Release file rather than from the transport.",
                "RUN set -eux; \\",
                "    if command -v apt-get >/dev/null 2>&1; then \\",
                "        printf '%s\\n' 'Acquire::http::Timeout \"20\";' \\",
                "            'Acquire::https::Timeout \"20\";' 'Acquire::Retries \"2\";' \\",
                "            > /etc/apt/apt.conf.d/99-sokar-timeouts; \\",
                "        for f in /etc/apt/sources.list.d/*.sources; do \\",
                "            [ -e \"$f\" ] || continue; \\",
                "            sed -i 's|^URIs:.*|URIs: " + sources + "|' \"$f\"; \\",
                "        done; \\",
                "    elif " + refusal + "; then \\",
                "        echo 'this project names image.package_sources, and " + project.baseImage()
                        + " has no apt to apply them to' >&2; exit 1; \\",
                "    fi",
                "",
                "RUN set -eux; \\",
                "    if command -v curl >/dev/null 2>&1 && command -v git >/dev/null 2>&1 \\",
                "        && command -v ssh >/dev/null 2>&1 && command -v tmux >/dev/null 2>&1; then :; \\",
                "    elif command -v apt-get >/dev/null 2>&1; then \\",
                "        apt-get update && apt-get install -y --no-install-recommends curl ca-certificates git openssh-client tmux \\",
                "        && rm -rf /var/lib/apt/lists/*; \\",
                "    elif command -v apk >/dev/null 2>&1; then apk add --no-cache curl ca-certificates git openssh-client tmux; \\",
                "    elif command -v dnf >/dev/null 2>&1; then dnf install -y curl ca-certificates git openssh-clients tmux && dnf clean all; \\",
                "    else echo 'no curl/git/ssh/tmux and no known package manager in " + project.baseImage() + "' >&2; exit 1; \\",
                "    fi",
                "",
                "",
                "# How much a session remembers, pinned rather than inherited. What 'sokar task",
                "# attach' can honestly claim on returning is exactly this many lines, and a",
                "# figure nobody chose is a figure nobody can state. Written as a file so it is",
                "# readable in the image rather than hidden in a command line.",
                "#",
                "# The terminal a program INSIDE the session sees is tmux's, not the one outside,",
                "# so passing a 256-colour TERM into the container is only half of it: tmux's own",
                "# default is 'screen', which is eight. Chosen at build time by asking this image",
                "# what it can resolve, because a default-terminal naming an entry the image lacks",
                "# is worse than the eight colours it was meant to replace.",
                "RUN mkdir -p /etc/sokar \\",
                "    && printf 'set -g history-limit " + SCROLLBACK + "\\n' > /etc/sokar/tmux.conf \\",
                "    && if infocmp tmux-256color >/dev/null 2>&1; then \\",
                "        printf 'set -g default-terminal \"tmux-256color\"\\n' >> /etc/sokar/tmux.conf; \\",
                "    elif infocmp screen-256color >/dev/null 2>&1; then \\",
                "        printf 'set -g default-terminal \"screen-256color\"\\n' >> /etc/sokar/tmux.conf; \\",
                "    fi",
                "",
                "# The agent never runs as root. A rootless podman user namespace already maps this",
                "# to an unprivileged host uid, so this is defense in depth rather than the only line.",
                "# No uid is pinned: 1000 is already taken on several common base images.",
                "RUN id -u agent >/dev/null 2>&1 || useradd --create-home --shell /bin/bash agent",
                "",
                "RUN mkdir -p /workspace && chown agent:agent /workspace"));

        // Everything above is the base image and the packages every task needs. Everything below
        // is the agent's. This ARG is the seam between them: podman invalidates its cache from the
        // line whose text changed, so passing a different value here rebuilds the agent's layers
        // and keeps the package layer - which is the difference between a rebuild that takes
        // seconds and one that downloads a distribution again.
        //
        // It is declared even when no agent contributes anything, so the seam is in the image
        // rather than appearing only when something happens to sit below it.
        lines.add("");
        lines.add("ARG " + LAYER_EPOCH + "=0");

        if (!layers.asRoot().isEmpty()) {
            lines.add("");
            lines.addAll(layers.asRoot());
        }

        lines.add("");
        lines.add("USER agent");
        lines.add("WORKDIR /workspace");
        lines.add("");
        lines.add("# Agents install into ~/.local/bin, which is not on PATH for a non-login");
        lines.add("# shell - and 'podman exec' is one. Without this the agent is present and");
        lines.add("# not findable, which reads as a broken install rather than a missing PATH.");
        lines.add("ENV PATH=/home/agent/.local/bin:/usr/local/bin:/usr/bin:/bin");
        lines.add("");
        // Measured on the Ubuntu VM, 2026-09-12: without this LANG is unset and LC_CTYPE is
        // POSIX, because neither the base image nor 'podman exec' sets one. An agent's terminal
        // interface then loses every character outside ASCII - its banner, its prompt markers and
        // its spinner all arrive as underscores - and the shell echoes what somebody types as
        // octal escapes. C.UTF-8 costs nothing: it is built into glibc and musl, so no locale has
        // to be generated and no package added.
        //
        // LANG rather than LC_ALL on purpose. LANG is the fallback every LC_* category uses when
        // it is not set itself, so this makes UTF-8 the default without taking away the ability
        // to set a category. LC_ALL would override anything the project or the agent chose.
        lines.add("# Without a UTF-8 locale an agent's interface arrives as underscores.");
        lines.add("ENV LANG=C.UTF-8");

        if (!layers.asAgent().isEmpty()) {
            lines.add("");
            lines.addAll(layers.asAgent());
        }

        return String.join("\n", lines) + "\n";
    }
}
