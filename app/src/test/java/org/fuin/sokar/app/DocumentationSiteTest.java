package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests that the documentation is one site somebody can find their way through: every page in its one navigation,
 * once, and no link that a published page could not follow.
 * <p>
 * The same checks {@code mkdocs build --strict} makes, held here so the reactor fails on them without Python: a
 * page nobody can reach and a link that leaves {@code doc/} by a relative path are what that build refused.
 */
@Tag("documents")
class DocumentationSiteTest {

    private static final Path DOC = Path.of("..", "doc");

    private static final Path SITE = Path.of("..", "mkdocs.yml");

    /** A Markdown link's target, without its title. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    private static List<Path> pages() throws Exception {
        try (var found = Files.list(DOC)) {
            return found.filter(page -> page.getFileName().toString().endsWith(".md")).sorted().toList();
        }
    }

    @Test
    void everyPageIsInTheNavigationExactlyOnce() throws Exception {
        final String site = Files.readString(SITE);
        for (final Path page : pages()) {
            final String name = page.getFileName().toString();
            final Matcher entry = Pattern.compile("(?m)[: ]" + Pattern.quote(name) + "\\s*$").matcher(site);
            int count = 0;
            while (entry.find()) {
                count++;
            }
            assertThat(count).as("'%s' in mkdocs.yml's navigation", name).isEqualTo(1);
        }
    }

    @Test
    void everyLinkAPublishedPageHasCanBeFollowed() throws Exception {
        final List<String> broken = new ArrayList<>();
        for (final Path page : pages()) {
            final Matcher link = LINK.matcher(Files.readString(page));
            while (link.find()) {
                final String target = link.group(1);
                if (target.startsWith("http://") || target.startsWith("https://") || target.startsWith("mailto:")
                        || target.startsWith("#")) {
                    continue;
                }
                final String file = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
                final Path resolved = DOC.resolve(file).normalize();
                // A relative link out of doc/ works in the repository and not on the site: absolute, or not at all.
                if (!resolved.startsWith(DOC.normalize()) || !Files.exists(resolved)) {
                    broken.add(page.getFileName() + " -> " + target);
                }
            }
        }
        assertThat(broken).as("links a published page could not follow").isEmpty();
    }
}
