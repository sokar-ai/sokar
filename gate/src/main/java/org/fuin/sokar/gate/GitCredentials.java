package org.fuin.sokar.gate;

import java.util.List;
import java.util.Map;

/**
 * What a git command needs to reach a remote, asked for by URL.
 * <p>
 * <strong>An interface here and the vault elsewhere.</strong> This module runs the git commands;
 * the credential comes from the vault, which this module cannot see and should not. So the caller
 * that has both hands one of these in, and the gate asks it per URL - which keeps "where the
 * secret comes from" in one place while every git command that reaches a real remote goes through
 * it.
 * <p>
 * <strong>Where the work came from does not change the answer.</strong> An agent in a container
 * that pushed to the gate, a person running {@code gate approve}, a timer measuring how far a
 * mirror is behind: the connection to the forge is made by this host either way, and is
 * authenticated the same way.
 */
public interface GitCredentials {

    /** Nothing is lent. What a caller with no vault passes, and what tests use. */
    GitCredentials NONE = url -> Lease.EMPTY;

    /**
     * Lends what this URL needs, for as long as the lease is open.
     *
     * @param url The remote a git command is about to be pointed at.
     * @return The lease. Never {@code null}; it may hold nothing.
     */
    Lease forUrl(String url);

    /** What one git command may use, and the undoing of it. */
    interface Lease extends AutoCloseable {

        /** A lease that lends nothing. */
        Lease EMPTY = new Lease() {

            @Override
            public Map<String, String> environment() {
                return Map.of();
            }

            @Override
            public List<String> arguments() {
                return List.of();
            }

            @Override
            public void close() {
                // Nothing was taken out.
            }
        };

        /**
         * Returns what to add to the git process's environment.
         *
         * @return Variables, possibly empty.
         */
        Map<String, String> environment();

        /**
         * Returns what to put on git's command line before the verb.
         *
         * @return Arguments, possibly empty. Never a secret - a name at most.
         */
        List<String> arguments();

        @Override
        void close();
    }
}
