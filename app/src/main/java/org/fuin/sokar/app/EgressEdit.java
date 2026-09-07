package org.fuin.sokar.app;

import java.util.ArrayList;
import java.util.List;

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

        replace(block, "sets", flow(sets, false));
        replace(block, "domains", flow(domains, true));

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
     * Replaces one key inside the block, or removes it when the value is empty.
     *
     * @param block Lines between the {@code egress:} line and the next top-level key.
     * @param key {@code sets} or {@code domains}.
     * @param value Rendered flow list, or {@code null} when the key should go.
     */
    private static void replace(List<String> block, String key, String value) {

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
            block.add(lastKeyIndex(block) + 1, "  " + key + ": " + value);
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
    private static String flow(List<String> values, boolean quoted) {
        if (values.isEmpty()) {
            return null;
        }
        final String inner = String.join(", ", values.stream()
                .map(value -> quoted ? "\"" + value + "\"" : value).toList());
        return "[" + inner + "]";
    }
}
