package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.sokar.core.process.Command;
import org.fuin.sokar.core.process.CommandResult;
import org.jspecify.annotations.Nullable;

/**
 * The host keys this machine trusts, and the ones a host offers.
 * <p>
 * <strong>An unknown host key is a question, not a decision.</strong> Accepting on first use is
 * what everybody does and it is what an interception looks like; refusing silently leaves a daemon
 * stuck with no way to say why. So a host nobody has vouched for stops the work, and what stopped
 * it is answered with every key that host offered - type and fingerprint, as this machine saw
 * them - so a person can compare them with what they were told and say which one is real.
 * <p>
 * <strong>Neither is accept-new, anywhere.</strong> Not on a fetch somebody started, and not on
 * the timer either: a machine that quietly learns a host at three in the morning has decided the
 * question that this refuses to decide. The first follow is where a person is present, and that
 * is where it is settled. The operator's ruling, 2026-09-19.
 */
public final class HostKeys {

    private HostKeys() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * One key a host offers.
     *
     * @param type The algorithm, as ssh names it.
     * @param fingerprint {@code SHA256:...}, the form ssh itself prints and a person is told.
     * @param line The {@code known_hosts} line it would be recorded as. Not shown to anybody; it
     *        is what {@link #trust} writes once somebody has confirmed the fingerprint.
     */
    public record Offered(String type, String fingerprint, String line) {

        /**
         * Returns this as plain values, without the line.
         *
         * @return type and fingerprint.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", type);
            map.put("fingerprint", fingerprint);
            return map;
        }
    }

    /** What git says when it has not met the host. */
    private static final String UNKNOWN = "Host key verification failed";

    /** What ssh says when it has met the host and the key is not the one it remembers. */
    private static final String CHANGED = "REMOTE HOST IDENTIFICATION HAS CHANGED";

    /**
     * Tells whether git failed over the host's key rather than over a credential.
     *
     * @param said What git wrote.
     * @return {@code true} when this is about the host key.
     */
    public static boolean refusedTheHost(final String said) {
        return said.contains(UNKNOWN) || said.contains(CHANGED) || said.contains("ssh-askpass");
    }

    /**
     * Tells whether the host's key is not the one this machine remembers.
     *
     * @param said What git wrote.
     * @return {@code true} for a key that changed, which is the one to be afraid of.
     */
    public static boolean changed(final String said) {
        return said.contains(CHANGED);
    }

    /**
     * Returns the keys a host offers right now.
     *
     * @param context The machine.
     * @param host The host, without a user or a port.
     * @return What it offered, empty when it could not be reached.
     */
    public static List<Offered> offeredBy(final SokarContext context, final String host) {
        final CommandResult scanned = context.runner().run(
                Command.of("ssh-keyscan", "-T", "10", host));
        if (!scanned.successful() && scanned.standardOutput().isBlank()) {
            return List.of();
        }
        final List<Offered> offered = new ArrayList<>();
        for (final String line : scanned.standardOutput().split("\n")) {
            final String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            final String[] fields = stripped.split("\\s+");
            if (fields.length < 3) {
                continue;
            }
            final String fingerprint = fingerprintOf(fields[2]);
            if (!fingerprint.isEmpty()) {
                offered.add(new Offered(fields[1], fingerprint, stripped));
            }
        }
        return List.copyOf(offered);
    }

    /**
     * Records one key for a host, and only if the host still offers exactly that one.
     * <p>
     * <strong>The fingerprint comes from the person.</strong> They were shown what the host
     * offered, they compared it with what they were told out of band, and they say which. Asking
     * the host again here is not a second opinion - it is what stops a key that arrived between
     * being shown and being confirmed from being the one that is written.
     *
     * @param context The machine.
     * @param host The host.
     * @param fingerprint What the person confirmed, {@code SHA256:...}.
     * @return What was recorded, or {@code null} when the host offers no such key now.
     * @throws IOException If the file cannot be written.
     */
    public static @Nullable Offered trust(final SokarContext context, final String host,
            final String fingerprint) throws IOException {
        final String wanted = fingerprint.strip();
        for (final Offered candidate : offeredBy(context, host)) {
            if (candidate.fingerprint().equals(wanted)) {
                final Path file = FollowCredential.knownHostsFile(context);
                Files.createDirectories(file.getParent());
                final String existing = Files.exists(file)
                        ? Files.readString(file, StandardCharsets.UTF_8) : "";
                if (!existing.contains(candidate.line())) {
                    Files.writeString(file, existing
                            + (existing.isEmpty() || existing.endsWith("\n") ? "" : "\n")
                            + candidate.line() + "\n", StandardCharsets.UTF_8);
                }
                return candidate;
            }
        }
        return null;
    }

    /**
     * Tells whether this machine already remembers a key for a host.
     *
     * @param context The machine.
     * @param host The host.
     * @return {@code true} when something is recorded for it.
     */
    public static boolean known(final SokarContext context, final String host) {
        final Path file = FollowCredential.knownHostsFile(context);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .anyMatch(line -> line.startsWith(host + " ") || line.startsWith("[" + host));
        } catch (final IOException ex) {
            return false;
        }
    }

    private static String fingerprintOf(final String base64) {
        try {
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Base64.getDecoder().decode(base64)));
        } catch (final java.security.NoSuchAlgorithmException | IllegalArgumentException ex) {
            return "";
        }
    }
}
