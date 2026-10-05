package org.fuin.sokar.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.sokar.vault.OpenSshPrivateKey;
import org.jspecify.annotations.Nullable;

/**
 * The ssh keys this account already has, described without their values.
 * <p>
 * <strong>Why the machine has to answer this.</strong> Deciding what in {@code ~/.ssh} is a
 * private key means reading the files, and an interface cannot read a machine's home directory -
 * so it was left offering a path typed from memory. The operator typed two wrong ones in an
 * afternoon: once a path from his laptop, once the {@code .pub} beside the key he meant. Both were
 * recorded as perfectly good records pointing at nothing.
 * <p>
 * <strong>No value is read out of here.</strong> A private key is opened far enough to say what it
 * is and whether a passphrase protects it, and is never copied, printed or returned.
 */
public final class SshKeys {

    /** Where a key was found. */
    public enum Found {

        /** In the account's {@code ~/.ssh} directory. */
        DIRECTORY,

        /** Named by an {@code IdentityFile} line in {@code ~/.ssh/config}. */
        CONFIGURED
    }

    /**
     * One key, described.
     *
     * @param path Where it is.
     * @param type The algorithm, or "" when a passphrase hides it.
     * @param fingerprint {@code SHA256:...} of the public half, or "" when it cannot be worked
     *        out - which happens for an encrypted key with no {@code .pub} beside it.
     * @param comment What the public half calls it, usually an address. Often the only thing that
     *        tells two keys apart to the person who made them.
     * @param encrypted Whether a passphrase protects it.
     * @param privateHalf Whether the private file is there. A {@code .pub} on its own is not a
     *        credential, and is the mistake anybody makes once.
     * @param usable Whether this machine can sign with it as it stands.
     * @param found Where it was found.
     */
    public record Key(String path, String type, String fingerprint, String comment,
            boolean encrypted, boolean privateHalf, boolean usable, Found found) {

        /**
         * Returns this as plain values, for a caller that has to put it on a wire.
         *
         * @return The key, never its value.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("path", path);
            map.put("type", type);
            map.put("fingerprint", fingerprint);
            map.put("comment", comment);
            map.put("encrypted", encrypted);
            map.put("privateHalf", privateHalf);
            map.put("usable", usable);
            map.put("found", found.name());
            return map;
        }

        /**
         * Returns why this key cannot be used as it stands, or {@code null}.
         *
         * @return One sentence with what to do.
         */
        public @Nullable String obstacle() {
            if (!privateHalf) {
                return "only the public half is here; the private key is what a machine signs with";
            }
            if (encrypted) {
                return "a passphrase protects it, and nothing here can ask for one."
                        + " Use it where it lies, or store a copy without a passphrase:"
                        + " 'ssh-keygen -p -f " + path + "' with an empty new passphrase";
            }
            if (!usable) {
                return "this machine signs with Ed25519 and this is "
                        + (type.isEmpty() ? "another kind" : type)
                        + "; it can still be used where it lies, by ssh itself";
            }
            return null;
        }
    }

    private final Path home;

    /**
     * Constructor.
     *
     * @param home The account's home directory.
     */
    public SshKeys(final Path home) {
        this.home = home;
    }

    /**
     * Returns every key this account has, the directory's first.
     *
     * @return What was found, without duplicates.
     */
    public List<Key> all() {
        final Set<Path> seen = new LinkedHashSet<>();
        final List<Key> keys = new ArrayList<>();
        for (final Path candidate : inDirectory()) {
            if (seen.add(candidate.toAbsolutePath().normalize())) {
                final Key key = describe(candidate, Found.DIRECTORY);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        // Named rather than guessed at. A company setup usually names its key in the config, and
        // a list that ignored it would look wrong to exactly the people who need this most.
        for (final Path candidate : configured()) {
            if (seen.add(candidate.toAbsolutePath().normalize())) {
                final Key key = describe(candidate, Found.CONFIGURED);
                if (key != null) {
                    keys.add(key);
                }
            }
        }
        return List.copyOf(keys);
    }

    private List<Path> inDirectory() {
        final Path directory = home.resolve(".ssh");
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var entries = Files.list(directory)) {
            final List<Path> candidates = new ArrayList<>();
            for (final Path entry : entries.filter(Files::isRegularFile).sorted().toList()) {
                final String name = entry.getFileName().toString();
                if (!name.endsWith(".pub")) {
                    candidates.add(entry);
                    continue;
                }
                // A .pub whose private half is missing is offered as the private path it implies,
                // so the listing can say "only the public half is here" rather than showing
                // nothing. That is the case somebody points a credential at by mistake, and a
                // list that hides it cannot explain the failure that follows.
                final Path implied = directory.resolve(name.substring(0, name.length() - 4));
                if (!Files.exists(implied)) {
                    candidates.add(implied);
                }
            }
            return List.copyOf(candidates);
        } catch (final IOException ex) {
            return List.of();
        }
    }

    private List<Path> configured() {
        final Path config = home.resolve(".ssh").resolve("config");
        if (!Files.isRegularFile(config)) {
            return List.of();
        }
        final List<Path> named = new ArrayList<>();
        try {
            for (final String line : Files.readAllLines(config, StandardCharsets.UTF_8)) {
                final String stripped = line.strip();
                if (!stripped.toLowerCase(java.util.Locale.ROOT).startsWith("identityfile")) {
                    continue;
                }
                final String[] fields = stripped.split("\\s+", 2);
                if (fields.length < 2) {
                    continue;
                }
                String said = fields[1].strip().replace("\"", "");
                if (said.startsWith("~/")) {
                    said = home.resolve(said.substring(2)).toString();
                }
                named.add(Path.of(said));
            }
        } catch (final IOException ex) {
            return List.of();
        }
        return named;
    }

    /**
     * Describes one candidate, or answers {@code null} when it is not a key at all.
     *
     * @param path The file.
     * @param found Where it came from.
     * @return The key, or {@code null}.
     */
    private @Nullable Key describe(final Path path, final Found found) {
        final Path publicHalf = Path.of(path + ".pub");
        final String[] published = publicHalfOf(publicHalf);
        if (!Files.isReadable(path)) {
            // Only the public half is here. Worth listing rather than hiding: it is what somebody
            // points at by mistake, and a list that leaves it out cannot say why it is wrong.
            return published == null ? null
                    : new Key(path.toString(), published[0], fingerprintOf(published[1]),
                            published.length > 2 ? published[2] : "", false, false, false, found);
        }
        final String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (final IOException | java.io.UncheckedIOException ex) {
            return null;
        }
        final OpenSshPrivateKey.Described described = OpenSshPrivateKey.describe(text);
        if (described == null) {
            return null;
        }
        String type = described.type();
        String fingerprint = "";
        String comment = "";
        if (published != null) {
            // The public half is the cheap way to a fingerprint and the only way to a comment.
            type = type.isEmpty() ? published[0] : type;
            fingerprint = fingerprintOf(published[1]);
            comment = published.length > 2 ? published[2] : "";
        } else if (described.usable()) {
            // No .pub beside it: the public half is derivable from the seed, for the one kind
            // this machine can read.
            try {
                fingerprint = new org.fuin.sokar.vault.SigningKey(
                        Base64.getDecoder().decode(OpenSshPrivateKey.seedBase64(text)), "")
                        .authorizedKeysLine().split("\\s+")[1];
                fingerprint = fingerprintOf(fingerprint);
            } catch (final RuntimeException ex) {
                fingerprint = "";
            }
        }
        return new Key(path.toString(), type, fingerprint, comment, described.encrypted(), true,
                described.usable(), found);
    }

    /**
     * Returns the fields of a public key file.
     *
     * @param path The {@code .pub}.
     * @return type, base64 and comment, or {@code null}.
     */
    private static String @Nullable [] publicHalfOf(final Path path) {
        if (!Files.isReadable(path)) {
            return null;
        }
        try {
            final String[] fields = Files.readString(path, StandardCharsets.UTF_8)
                    .strip().split("\\s+", 3);
            return fields.length < 2 ? null : fields;
        } catch (final IOException ex) {
            return null;
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
