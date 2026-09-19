package org.fuin.sokar.app;

import java.util.List;
import java.util.Map;
import org.fuin.sokar.gate.GitCredentials;

/**
 * The vault, answering the gate's question about a URL.
 * <p>
 * The seam exists because the module that runs git cannot see the vault. This is the one
 * implementation that matters: it hands out what {@link FollowCredential} lends, so a push
 * forwarded by the gate reaches a private forge by the same key a follow fetches with and a task
 * pushes with.
 */
public final class VaultGitCredentials implements GitCredentials {

    private final SokarContext context;

    private final String label;

    /**
     * Constructor.
     *
     * @param context Where the vault is.
     * @param label Names the agent socket, so two commands cannot collide.
     */
    public VaultGitCredentials(final SokarContext context, final String label) {
        this.context = context;
        this.label = label;
    }

    @Override
    public Lease forUrl(final String url) {
        final FollowCredential lent = FollowCredential.open(context, url, label);
        return new Lease() {

            @Override
            public Map<String, String> environment() {
                return lent.environment();
            }

            @Override
            public List<String> arguments() {
                return lent.arguments();
            }

            @Override
            public void close() {
                lent.close();
            }
        };
    }
}
