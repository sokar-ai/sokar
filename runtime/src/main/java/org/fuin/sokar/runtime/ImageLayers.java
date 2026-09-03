package org.fuin.sokar.runtime;

import java.util.List;

/**
 * Container-build fragments contributed by something outside this module.
 * <p>
 * Plain lists of lines rather than an agent-shaped type, deliberately: {@code sokar-runtime} builds
 * images and must not learn what an agent is. Whoever knows about agents - {@code sokar-app} -
 * converts and passes them in.
 *
 * @param asRoot Fragments run before the image drops to the unprivileged user.
 * @param asAgent Fragments run as the agent user.
 */
public record ImageLayers(List<String> asRoot, List<String> asAgent) {

    /**
     * Constructor with all data.
     *
     * @param asRoot Fragments run as root.
     * @param asAgent Fragments run as the agent user.
     */
    public ImageLayers {
        asRoot = List.copyOf(asRoot);
        asAgent = List.copyOf(asAgent);
    }

    /**
     * Returns layers that add nothing.
     *
     * @return Empty layers.
     */
    public static ImageLayers none() {
        return new ImageLayers(List.of(), List.of());
    }

    /**
     * Returns these layers with more appended.
     *
     * @param moreAsRoot Additional root fragments.
     * @param moreAsAgent Additional agent-user fragments.
     * @return Combined layers.
     */
    public ImageLayers and(List<String> moreAsRoot, List<String> moreAsAgent) {
        final List<String> root = new java.util.ArrayList<>(asRoot);
        appendSeparated(root, moreAsRoot);
        final List<String> agent = new java.util.ArrayList<>(asAgent);
        appendSeparated(agent, moreAsAgent);
        return new ImageLayers(root, agent);
    }

    private static void appendSeparated(List<String> target, List<String> addition) {
        if (addition.isEmpty()) {
            return;
        }
        if (!target.isEmpty()) {
            target.add("");
        }
        target.addAll(addition);
    }

    /**
     * Tells whether these layers add anything.
     *
     * @return {@code true} if both lists are empty.
     */
    public boolean isEmpty() {
        return asRoot.isEmpty() && asAgent.isEmpty();
    }
}
