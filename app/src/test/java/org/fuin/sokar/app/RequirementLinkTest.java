package org.fuin.sokar.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Refuses a link that points at a requirement's file rather than at the index.
 * <p>
 * <strong>A pointer is written for the day the thing it points at succeeds.</strong> A finished
 * requirement is deleted, file and index row together, so a link to the file breaks exactly when
 * that requirement works - and a link to a design document recording an undecided question breaks
 * the day the question is answered, because an answered question is deleted the same way. Both
 * were in {@code doc/authentication.md} on 2026-09-20, unnoticed for longer than the rule that
 * forbids them has existed; two other repositories found the same defect the same morning, each
 * while doing something else.
 * <p>
 * The index is the one page that may name files, because naming them is what an index is.
 */
class RequirementLinkTest {

    /**
     * A file under {@code issues/} that is deleted when its subject is settled: a numbered
     * requirement, or a design document recording a question.
     * <p>
     * Not everything under that directory dies. {@code Credential-Types-Compared.md} and
     * {@code Agents-And-Providers-Compared.md} are standing documents and may be linked directly -
     * a rule refusing every link under {@code issues/} would forbid a correct one, which
     * Agent Frontend found in his own repository before it could bite here.
     */
    private static final Pattern DIES =
            Pattern.compile("/[A-Z]\\d{2}-[^/]*\\.md$|_design\\.md$");

    /** A markdown link's target, without the title some links carry. */
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)");

    /**
     * A requirement cited the way the conventions require it: the number, then the index.
     * <p>
     * <strong>What sits between them is not only whitespace.</strong> These documents wrap at 100
     * columns, so the index link lands on the next line with its indent; and a citation is often
     * emphasised - {@code **This is the rehearsal for B08** ([index](README.md))} in B59, which a
     * pattern allowing only spaces cannot see. Bold is invisible to a reader and fatal to a regex.
     * Found by Agent Sluice in a fourth repository and measured here before it could hide a stale
     * pointer.
     */
    private static final Pattern POINTER =
            Pattern.compile("\\bB(\\d{2})\\b[\\s*_`]{0,10}\\(?\\[index\\]\\(");

    @Test
    void refusesALinkToARequirementFileFromAnywhereButTheIndex() throws IOException {
        // A guard that scans an empty list passes forever, which is the one way this rots in
        // silence - so what it walked is asserted before what it found.
        assertThat(pagesUnder(Path.of(".."))).as("markdown pages walked").hasSizeGreaterThan(20);
        assertThat(offendingLinksUnder(Path.of("..")))
                .as("a link into issues/ must name an index, because a finished requirement is"
                        + " deleted and takes its file with it")
                .isEmpty();
    }

    /**
     * Refuses a pointer to a requirement that no longer exists.
     * <p>
     * <strong>A cited number is a pointer or a record, and only one of them may go stale.</strong>
     * "Rescued from B24 on 2026-09-13" is a record: it says where a paragraph came from, it is as
     * true as the day it was written, and repairing it would destroy history. "B57
     * ([index](...))" is a pointer: it sends somebody to a requirement, and it is worth nothing
     * once that requirement is gone. Telling them apart by reading the sentence is what nobody
     * could make mechanical - so this asks the *shape* instead, which the conventions already
     * prescribe: a pointer carries the index beside the number, a record does not. That makes the
     * classification true by construction rather than by luck, and the cost is named: a bare
     * number meant as a pointer is not checked here, because it already breaks the rule that says
     * to write the index beside it.
     *
     * @throws IOException If a file cannot be read.
     */
    @Test
    void refusesAPointerToARequirementThatIsGone() throws IOException {
        final Path root = Path.of("..");
        final java.util.Set<String> existing = requirementsUnder(root);
        assertThat(existing).as("requirements found under issues/base").isNotEmpty();

        final List<String> dangling = new ArrayList<>();
        // The WHOLE text, not line by line: these documents wrap at 100 columns, so a pointer's
        // index link lands on the next line as often as not. A line-by-line scan simply does not
        // see those, and it reports green - which is worse than seeing them and being wrong.
        for (final Path page : pagesUnder(root)) {
            final String text = Files.readString(page);
            final Matcher pointer = POINTER.matcher(text);
            while (pointer.find()) {
                final String cited = "B" + pointer.group(1);
                if (!existing.contains(cited)) {
                    dangling.add(relative(root, page) + ":" + lineOf(text, pointer.start())
                            + " -> " + cited);
                }
            }
        }
        assertThat(dangling)
                .as("a pointer is written for the day the thing it points at is gone")
                .isEmpty();
    }

    private static int lineOf(String text, int offset) {
        return (int) text.substring(0, offset).chars().filter(c -> c == '\n').count() + 1;
    }

    private static java.util.Set<String> requirementsUnder(Path root) throws IOException {
        final Pattern named = Pattern.compile("^(B\\d{2})-.*\\.md$");
        try (Stream<Path> files = Files.list(root.resolve("issues").resolve("base"))) {
            return files.map(file -> named.matcher(file.getFileName().toString()))
                    .filter(Matcher::matches).map(match -> match.group(1))
                    .collect(java.util.stream.Collectors.toSet());
        }
    }

    /**
     * Returns every offending link, as {@code <file>:<line> -> <target>}.
     *
     * @param root Repository root.
     * @return Offenders, empty when the rule holds.
     * @throws IOException If a file cannot be read.
     */
    static List<Path> pagesUnder(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(RequirementLinkTest::isReadablePage).toList();
        }
    }

    static List<String> offendingLinksUnder(Path root) throws IOException {
        final List<String> offenders = new ArrayList<>();
        try (Stream<Path> tree = Files.walk(root)) {
            for (final Path page : tree.filter(RequirementLinkTest::isReadablePage).toList()) {
                offenders.addAll(offendingLinksIn(root, page));
            }
        }
        return offenders;
    }

    private static List<String> offendingLinksIn(Path root, Path page) throws IOException {
        // The index names files because that is what an index does. Everything else names it.
        if (page.getFileName().toString().equals("README.md") && relative(root, page)
                .startsWith("issues/")) {
            return List.of();
        }
        final List<String> offenders = new ArrayList<>();
        final List<String> lines = Files.readAllLines(page);
        for (int number = 1; number <= lines.size(); number++) {
            final Matcher link = LINK.matcher(lines.get(number - 1));
            while (link.find()) {
                final String target = link.group(1);
                if (target.contains("issues/") && DIES.matcher(target).find()) {
                    offenders.add(relative(root, page) + ":" + number + " -> " + target);
                }
            }
        }
        return offenders;
    }

    private static boolean isReadablePage(Path path) {
        final String text = path.toString();
        return text.endsWith(".md") && !text.contains("/target/") && !text.contains("/.git/")
                && Files.isRegularFile(path);
    }

    private static String relative(Path root, Path page) {
        return root.relativize(page).toString().replace('\\', '/');
    }
}
