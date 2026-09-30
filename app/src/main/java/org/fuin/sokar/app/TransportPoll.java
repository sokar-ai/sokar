package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.fuin.sokar.core.process.CommandRunner;

/**
 * Goes and asks every transport whether anything arrived.
 * <p>
 * The daemon binds no network interface, so nothing can be pushed to it: a message arrives because
 * this goes and looks. A transport that delivers straight into {@code inbound/} needs none of this
 * and says {@code poll: false}; one that keeps arrivals somewhere else - a spool directory, a
 * branch, a mailbox on another host - is asked here.
 * <p>
 * <strong>Which transport brought which message is worked out by looking, not by being told.</strong>
 * Each transport is asked on its own and {@code inbound/} is listed before and after, so what
 * appeared is what that one fetched. That matters because the host treats a message differently
 * depending on what its carrier promised - a transport that attests an owner has its messages held
 * when the attestation is missing - and a self-reported list would be exactly the claim that must
 * not be trusted. It also means a message that was already there, put down by something that does
 * not poll, is attributed to no transport and is expected to prove nothing.
 */
public final class TransportPoll {

    /** What a transport exits with when it cannot run at all. */
    public static final int CANNOT_RUN = 2;

    /**
     * What one pass of polling did.
     *
     * @param arrivals Message file name to the transport that fetched it.
     * @param failures Transport name to why it could not be polled.
     */
    public record Outcome(Map<String, String> arrivals, Map<String, String> failures) {
    }

    private final CommandRunner runner;

    private final TransportDirectory transports;

    /**
     * Constructor.
     *
     * @param runner How commands are run.
     * @param transports Where the adapters are.
     */
    public TransportPoll(final CommandRunner runner, final TransportDirectory transports) {
        this.runner = runner;
        this.transports = transports;
    }

    /**
     * Asks every installed transport that polls.
     *
     * @param mailbox The task's mailbox.
     * @return What arrived and from where, and what could not be asked.
     * @throws IOException Listing failed.
     */
    public Outcome poll(final Mailbox mailbox) throws IOException {
        return poll(mailbox, null);
    }

    /**
     * Asks the installed transports that poll, of those named.
     * <p>
     * <strong>Only what a peer names</strong> (decided 2026-09-29): a transport installed machine-wide is
     * otherwise asked by every account's daemon, including accounts that have nothing configured for it, and
     * answers 78 on every cycle.
     *
     * @param mailbox The task's mailbox.
     * @param named The schemes a peer of its project names, or {@code null} for every transport.
     * @return What arrived and from where, and what could not be asked.
     * @throws IOException Listing failed.
     */
    public Outcome poll(final Mailbox mailbox, final java.util.@org.jspecify.annotations.Nullable Set<String> named)
            throws IOException {
        final Map<String, String> arrivals = new LinkedHashMap<>();
        final Map<String, String> failures = new LinkedHashMap<>();
        if (!Files.isDirectory(mailbox.inbound())) {
            // Nothing that carries a message creates a directory: a mailbox missing its inbound is
            // repaired where it is made, not papered over here.
            return new Outcome(arrivals, failures);
        }
        for (final Map.Entry<String, Path> transport : transports.byName().entrySet()) {
            if (named != null && !named.contains(transport.getKey())) {
                continue;
            }
            final TransportDescription described =
                    TransportDescription.of(runner, transport.getValue());
            if (!described.polls() || described.keepsConversation()) {
                // A conversation is polled once per project beside the passes, with the project's secrets.
                continue;
            }
            final Set<String> before = listing(mailbox.inbound());
            final CommandResult result;
            try {
                result = runner.run(Command.of(transport.getValue().toString(), "poll",
                        "--into", mailbox.inbound().toString()));
            } catch (final RuntimeException ex) {
                failures.put(transport.getKey(), String.valueOf(ex.getMessage()));
                continue;
            }
            if (result.exitCode() != 0) {
                failures.put(transport.getKey(), result.exitCode() == CANNOT_RUN
                        ? "it cannot run: " + result.standardError().strip()
                        : "it stopped with " + result.exitCode() + ": "
                                + result.standardError().strip());
                // What it fetched before failing still counts: it is in the directory, and
                // pretending otherwise would leave messages nobody ever looks at again.
            }
            for (final String name : listing(mailbox.inbound())) {
                if (!before.contains(name)) {
                    arrivals.put(name, transport.getKey());
                }
            }
        }
        return new Outcome(arrivals, failures);
    }

    /**
     * Lists the messages in a directory, and only those.
     * <p>
     * A message arrives with a {@code .sig} beside it and possibly a {@code .owner}, and counting
     * those as arrivals would report three where one came - and would put names in the map that
     * nothing ever looks up. A message is the file ending in {@code .json}; its companions end in
     * something else by construction.
     *
     * @param directory The inbound directory.
     * @return The message file names.
     * @throws IOException Listing failed.
     */
    private Set<String> listing(final Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            final List<String> names = new ArrayList<>(entries.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json")).toList());
            return new LinkedHashSet<>(names);
        }
    }
}
