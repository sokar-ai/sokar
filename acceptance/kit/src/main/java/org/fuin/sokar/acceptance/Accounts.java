package org.fuin.sokar.acceptance;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The accounts a run's scenarios act as on the machine - one, or one per worker thread when features run in
 * parallel.
 * <p>
 * <strong>An account is the unit of isolation.</strong> Everything Sokar keeps is per account: its image
 * store, its runtime and state directories, its daemon and socket, its keyring and vault. So features that run
 * at the same time do so under different accounts and cannot see each other - and a run that does this proves
 * on the way that several people can use one machine.
 * <p>
 * <strong>Per thread, and a thread's for the whole run.</strong> The engine runs a feature's scenarios in order
 * on one thread ({@code execution-mode.feature=same_thread}), so a feature keeps its account from its first
 * scenario to its last, which is what the features that share state across their scenarios need. A thread
 * that asks when every account is held by another fails loudly: two features under one account at once would
 * meet in its daemon and its projects, and fail as something else.
 * <p>
 * Without {@value #USERS}, the one account {@code sokar.acceptance.user} names serves every scenario, as it
 * always did - which is what an agent repository's leg keeps unless it asks for more.
 */
public final class Accounts {

    /** The property naming several accounts, comma-separated. */
    public static final String USERS = "sokar.acceptance.users";

    private static final Map<Thread, String> HELD = new ConcurrentHashMap<>();

    private static final Deque<String> FREE = new ArrayDeque<>();

    private static List<String> all = List.of();

    private Accounts() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the account the calling thread acts as, handing it the next free one the first time it asks.
     *
     * @return The account's name.
     * @throws AssertionError When every account is held by another thread - more threads than accounts.
     */
    public static synchronized String forThisThread() {
        final Thread thread = Thread.currentThread();
        final String held = HELD.get(thread);
        if (held != null) {
            return held;
        }
        if (all.isEmpty()) {
            all = configured();
            FREE.addAll(all);
        }
        final String next = FREE.pollFirst();
        if (next == null) {
            throw new AssertionError("More threads than accounts: every one of " + all + " is held, and "
                    + thread.getName() + " asked for one. Give the run as many accounts as its parallelism.");
        }
        HELD.put(thread, next);
        return next;
    }

    /**
     * Returns every account the run was given.
     *
     * @return The accounts, in the order they were named.
     */
    public static synchronized List<String> all() {
        if (all.isEmpty()) {
            all = configured();
            FREE.addAll(all);
        }
        return all;
    }

    /**
     * Reads the accounts the run was given.
     *
     * @return {@value #USERS}, or the one {@code sokar.acceptance.user} when it names none.
     */
    static List<String> configured() {
        final String several = System.getProperty(USERS, "");
        final List<String> accounts = new ArrayList<>();
        for (final String each : several.split(",")) {
            if (!each.isBlank()) {
                accounts.add(each.strip());
            }
        }
        if (accounts.size() > 1 && !System.getProperty("sokar.acceptance.as", "").isBlank()) {
            // Every account would drive the one operator's Sokar, and meet there.
            throw new IllegalStateException("sokar.acceptance.as drives one operator's Sokar; it cannot be"
                    + " combined with several accounts in " + USERS);
        }
        if (!accounts.isEmpty()) {
            return List.copyOf(accounts);
        }
        final String one = System.getProperty("sokar.acceptance.user");
        if (one == null || one.isBlank()) {
            throw new IllegalStateException("Set sokar.acceptance.user, or sokar.acceptance.users for several accounts");
        }
        return List.of(one);
    }

}
