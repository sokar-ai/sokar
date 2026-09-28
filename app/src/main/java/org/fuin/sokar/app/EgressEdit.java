package org.fuin.sokar.app;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Rewrites the {@code egress} block of a project file and leaves everything else byte for byte.
 * <p>
 * <strong>Text, not a document.</strong> Reading the file into a model and dumping it back would
 * be shorter and would throw away every comment, the key order and the quoting an operator chose -
 * in the one file in this product that is meant to be read by a person and reviewed in a diff. So
 * the two keys this edits are found and replaced where they stand, and a line this does not
 * understand is a line it does not touch.
 * <p>
 * The result is always parsed before it is written, by the reader that would have to read it
 * later, so a surgical edit that produced something the project reader refuses fails here rather
 * than at the next task.
 */
final class EgressEdit {

    private EgressEdit() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the file with its egress declaration replaced.
     *
     * @param original The file as it is now.
     * @param sets Set names the project should name, in order.
     * @param domains Hosts the project should name directly, in order.
     * @return The new content.
     */
    static String withEgress(String original, List<String> sets, List<String> domains) {

        final List<String> lines = new ArrayList<>(List.of(original.split("\n", -1)));
        final int start = indexOfEgress(lines);

        if (start < 0) {
            return sets.isEmpty() && domains.isEmpty() ? original
                    : appended(original, sets, domains);
        }

        final int end = endOfBlock(lines, start);
        final List<String> block = new ArrayList<>(lines.subList(start + 1, end));

        replace(block, "sets", flow(sets, false), "  ");
        replace(block, "domains", flow(domains, true), "  ");

        final List<String> rewritten = new ArrayList<>(lines.subList(0, start));
        if (block.stream().anyMatch(line -> !line.isBlank())) {
            rewritten.add(lines.get(start));
            rewritten.addAll(block);
        }
        // Otherwise the whole block goes: a bare 'egress:' reads as a declaration and means
        // nothing, which is the ambiguity this key exists to avoid.
        rewritten.addAll(lines.subList(end, lines.size()));
        return String.join("\n", rewritten);
    }

    /**
     * Returns the file with one repository's egress declaration replaced.
     * <p>
     * <strong>Why a repository's block and not the project's.</strong> A connection a task made is
     * a fact about the repository that task works on. Remembering it at project level would widen
     * every other repository of that project for a reason none of them can see - which is exactly
     * what a per-repository declaration exists to end.
     * <p>
     * <strong>The project's own repository is not in here.</strong> It has no entry under
     * {@code repositories:}; its egress is the project's, so a caller asking about it passes
     * {@code null} and gets {@link #withEgress(String, List, List)}.
     * <p>
     * <strong>A repository this file does not name is refused rather than written anywhere.</strong>
     * Falling back to the top level would put the grant in the one place this method exists to keep
     * it out of, and it would look as though it had worked.
     *
     * @param original The file as it is now.
     * @param repository Which repository's block to edit, or {@code null} for the project's own.
     * @param sets Set names that repository should name, in order.
     * @param domains Hosts it should name directly, in order.
     * @return The new content.
     * @throws IllegalArgumentException If the file names no such repository.
     */
    static String withEgress(String original, @org.jspecify.annotations.Nullable String repository,
            List<String> sets, List<String> domains) {

        if (repository == null || repository.isBlank()) {
            return withEgress(original, sets, domains);
        }

        final List<String> lines = new ArrayList<>(List.of(original.split("\n", -1)));
        final int repositoriesAt = indexOfKey(lines, 0, lines.size(), 0, "repositories");
        if (repositoriesAt < 0) {
            throw new IllegalArgumentException("this project file names no repositories, so there"
                    + " is no '" + repository + "' block to write into");
        }
        final int repositoriesEnd = endOfBlock(lines, repositoriesAt, 0);
        final int keyAt = indexOfRepository(lines, repositoriesAt + 1, repositoriesEnd, repository);
        if (keyAt < 0) {
            throw new IllegalArgumentException("this project file does not name a repository '"
                    + repository + "'");
        }

        final int keyIndent = indentOf(lines.get(keyAt));
        final int repositoryEnd = endOfBlock(lines, keyAt, keyIndent);
        // Taken from what is already under this repository rather than assumed, so a file written
        // with four spaces keeps its four spaces. A repository with nothing under it - a bare
        // 'name:', which is a repository with no upstream - has nothing to take it from.
        final int childIndent = firstChildIndent(lines, keyAt + 1, repositoryEnd, keyIndent + 2);
        final String childPad = " ".repeat(childIndent);
        final String keyPad = " ".repeat(childIndent + 2);

        final int egressAt = indexOfKey(lines, keyAt + 1, repositoryEnd, childIndent, "egress");
        if (egressAt < 0) {
            if (sets.isEmpty() && domains.isEmpty()) {
                return original;
            }
            final List<String> added = new ArrayList<>();
            added.add(childPad + "egress:");
            if (!sets.isEmpty()) {
                added.add(keyPad + "sets: " + flow(sets, false));
            }
            if (!domains.isEmpty()) {
                added.add(keyPad + "domains: " + flow(domains, true));
            }
            final List<String> rewritten = new ArrayList<>(lines.subList(0, keyAt + 1));
            final List<String> body = new ArrayList<>(
                    lines.subList(keyAt + 1, repositoryEnd));
            // Beside the repository's other keys rather than after the blank lines that follow it,
            // by the same rule a new key inside a block follows.
            body.addAll(lastKeyIndex(body) + 1, added);
            rewritten.addAll(body);
            rewritten.addAll(lines.subList(repositoryEnd, lines.size()));
            return String.join("\n", rewritten);
        }

        final int egressEnd = endOfBlock(lines, egressAt, childIndent);
        final List<String> block = new ArrayList<>(lines.subList(egressAt + 1, egressEnd));
        replace(block, "sets", flow(sets, false), keyPad);
        replace(block, "domains", flow(domains, true), keyPad);

        final List<String> rewritten = new ArrayList<>(lines.subList(0, egressAt));
        if (block.stream().anyMatch(line -> !line.isBlank())) {
            rewritten.add(lines.get(egressAt));
            rewritten.addAll(block);
        }
        rewritten.addAll(lines.subList(egressEnd, lines.size()));
        return String.join("\n", rewritten);
    }

    /**
     * Returns the line a repository's key is on, or {@code -1}.
     * <p>
     * The key alone on its line, which is what both a repository with a mapping under it and a
     * bare one look like. Anything else - an inline mapping, say - is left for the caller to
     * refuse rather than guessed at: this rewrites somebody's file.
     */
    private static int indexOfRepository(List<String> lines, int from, int to, String name) {
        int childIndent = -1;
        for (int i = from; i < to && i < lines.size(); i++) {
            final String line = lines.get(i);
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            final int indent = indentOf(line);
            if (childIndent < 0) {
                childIndent = indent;
            }
            if (indent == childIndent && line.strip().equals(name + ":")) {
                return i;
            }
        }
        return -1;
    }

    /** Returns the indent of the first key inside a block, or the given default when it has none. */
    private static int firstChildIndent(List<String> lines, int from, int to, int fallback) {
        for (int i = from; i < to && i < lines.size(); i++) {
            final String line = lines.get(i);
            if (!line.isBlank() && !line.strip().startsWith("#")) {
                return indentOf(line);
            }
        }
        return fallback;
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    /** Returns the line a key of exactly this indent sits on, or {@code -1}. */
    private static int indexOfKey(List<String> lines, int from, int to, int indent, String key) {
        for (int i = from; i < to && i < lines.size(); i++) {
            final String line = lines.get(i);
            if (line.isBlank() || line.strip().startsWith("#")) {
                continue;
            }
            if (indentOf(line) == indent && line.strip().startsWith(key + ":")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the first line after a block, which is the first line at or above its own indent.
     * <p>
     * <strong>A comment ends a block exactly as a key does.</strong> Only blank lines are skipped.
     * A comment sitting at the outer indent introduces whatever comes next - it is the sentence
     * somebody wrote about the repository below, not about the block above - so swallowing it
     * would move it, and would make an emptied block look as though it still held something.
     * Found by a test that emptied a repository's declaration and got a bare {@code egress:} left
     * behind, with the next repository's comment inside it.
     * <p>
     * This is what the top-level form has always done, where a comment at column zero ends the
     * block; the two must not differ.
     */
    private static int endOfBlock(List<String> lines, int start, int indent) {
        for (int i = start + 1; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (indentOf(line) <= indent) {
                return i;
            }
        }
        return lines.size();
    }

    /**
     * Replaces one key inside the block, or removes it when the value is empty.
     *
     * @param block Lines between the {@code egress:} line and the next top-level key.
     * @param key {@code sets} or {@code domains}.
     * @param value Rendered flow list, or {@code null} when the key should go.
     * @param pad What to indent a key with when the block does not have one yet. Taken from the
     *        block rather than assumed, so a nested declaration lines up with its neighbours.
     */
    private static void replace(List<String> block, String key, @Nullable String value, String pad) {

        for (int i = 0; i < block.size(); i++) {
            final String line = block.get(i);
            if (!line.strip().startsWith(key + ":")) {
                continue;
            }
            final String indent = line.substring(0, line.indexOf(key));
            // A block list keeps its items on the lines below, and they belong to the key being
            // replaced. Removed here, because the flow form written back holds them all.
            int last = i;
            while (last + 1 < block.size() && block.get(last + 1).strip().startsWith("- ")) {
                last++;
            }
            block.subList(i, last + 1).clear();
            if (value != null) {
                block.add(i, indent + key + ": " + value);
            }
            return;
        }
        if (value != null) {
            block.add(lastKeyIndex(block) + 1, pad + key + ": " + value);
        }
    }

    /**
     * Returns the index of the last line that carries a key, so a new one lands beside it rather
     * than after the blank lines and comments that follow the block.
     *
     * @param block The block's lines.
     * @return Index, or {@code -1} when there is no key yet.
     */
    private static int lastKeyIndex(List<String> block) {
        int last = -1;
        for (int i = 0; i < block.size(); i++) {
            if (!block.get(i).isBlank() && !block.get(i).strip().startsWith("#")) {
                last = i;
            }
        }
        return last;
    }

    private static int indexOfEgress(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            final String line = lines.get(i);
            // Top level only: a key of the same name nested under something else is not this one.
            if (line.startsWith("egress:")) {
                return i;
            }
        }
        return -1;
    }

    private static int endOfBlock(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            final String line = lines.get(i);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("\t")) {
                return i;
            }
        }
        return lines.size();
    }

    private static String appended(String original, List<String> sets, List<String> domains) {
        final StringBuilder out = new StringBuilder(original);
        if (!original.isEmpty() && !original.endsWith("\n")) {
            out.append('\n');
        }
        out.append("egress:\n");
        if (!sets.isEmpty()) {
            out.append("  sets: ").append(flow(sets, false)).append('\n');
        }
        if (!domains.isEmpty()) {
            out.append("  domains: ").append(flow(domains, true)).append('\n');
        }
        return out.toString();
    }

    /**
     * Renders a flow list, which is the form the project wizard writes and the documentation
     * shows.
     *
     * @param values The values.
     * @param quoted Whether each value is quoted, as a host name is in the shipped examples.
     * @return The rendered list, or {@code null} when there is nothing to render.
     */
    private static @Nullable String flow(List<String> values, boolean quoted) {
        if (values.isEmpty()) {
            return null;
        }
        final String inner = String.join(", ", values.stream()
                .map(value -> quoted ? "\"" + value + "\"" : value).toList());
        return "[" + inner + "]";
    }
}
