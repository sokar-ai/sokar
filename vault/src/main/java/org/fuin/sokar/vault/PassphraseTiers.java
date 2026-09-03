package org.fuin.sokar.vault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Tries each passphrase source in turn and returns the first answer.
 * <p>
 * Terok ships five tiers. C3's analysis is that they overlap heavily and that
 * {@code passphrase-command} alone covers every password manager worth naming, so Sokar starts
 * with two and adds more when someone asks for one. Shipping fewer tiers is not a limitation here
 * but the point: every tier is another way for the passphrase to end up somewhere unexpected.
 */
public class PassphraseTiers implements PassphraseSource {

    private final List<PassphraseSource> tiers;

    /**
     * Constructor.
     *
     * @param tiers Sources, in the order they should be tried.
     */
    public PassphraseTiers(List<PassphraseSource> tiers) {
        this.tiers = List.copyOf(tiers);
    }

    /**
     * Constructor.
     *
     * @param tiers Sources, in the order they should be tried.
     */
    public PassphraseTiers(PassphraseSource... tiers) {
        this(Arrays.asList(tiers));
    }

    @Override
    public Optional<char[]> passphrase() {
        for (final PassphraseSource tier : tiers) {
            final Optional<char[]> found = tier.passphrase();
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the passphrase, or fails saying what was tried.
     *
     * @return The passphrase.
     * @throws VaultException If no tier produced one.
     */
    public char[] require() {
        return passphrase().orElseThrow(() -> new VaultException(
                "No passphrase available, tried: " + String.join(", ", names())));
    }

    /**
     * Returns the names of the configured tiers.
     *
     * @return Tier names, in order.
     */
    public List<String> names() {
        final List<String> names = new ArrayList<>();
        tiers.forEach(tier -> names.add(tier.name()));
        return List.copyOf(names);
    }

    @Override
    public String name() {
        return "tiers";
    }
}
